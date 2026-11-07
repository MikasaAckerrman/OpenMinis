//! [M-R2] The `GrammersApi` adapter — the ONLY file that touches the
//! grammers surface. It maps MTProto onto the domain types
//! (crate::telegram::types) and carries the read policy (0.3 s spacing
//! + FloodWait backoff) as a transport property, so handlers and tests
//! stay policy-free.

use crate::telegram::api::{with_read_policy, TgApi, TgError};
use crate::telegram::flood::RateLimiter;
use crate::telegram::format::{format_sender, MediaKind, RawMessage};
use crate::telegram::types::{ChatFull, DialogInfo, GlobalHit, MediaFile, UserInfo};
use grammers_client::{Client, Config, InitParams};
use grammers_session::Session;
use grammers_tl_types as tl;
use std::collections::HashMap;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Mutex;

pub struct GrammersApi {
    client: Client,
    rate: Mutex<RateLimiter>,
    peers: Mutex<HashMap<i64, tl::enums::InputPeer>>,
}

impl GrammersApi {
    pub async fn connect(storage: &Path, api_id: i32, api_hash: &str) -> Result<Self, String> {
        let session_path = Self::session_path(storage);
        let session = Session::load_file_or_create(&session_path)
            .map_err(|e| format!("minisd error: cannot load {}: {e}", session_path.display()))?;
        let client = Client::connect(Config {
            session,
            api_id,
            api_hash: api_hash.to_string(),
            params: InitParams {
                device_model: "minisd".into(),
                system_version: "android".into(),
                app_version: env!("CARGO_PKG_VERSION").into(),
                lang_code: "en".into(),
                ..Default::default()
            },
        })
        .await
        .map_err(|e| format!("minisd error: MTProto connect failed: {e}"))?;
        Ok(Self {
            client,
            rate: Mutex::new(RateLimiter::new()),
            peers: Mutex::new(HashMap::new()),
        })
    }

    pub fn session_path(storage: &Path) -> PathBuf {
        storage.join("grammers.session")
    }

    pub async fn is_authorized(&self) -> bool {
        self.client.is_authorized().await.unwrap_or(false)
    }

    async fn policy<T, F, Fut>(&self, f: F) -> Result<T, TgError>
    where
        F: FnMut() -> Fut,
        Fut: Future<Output = Result<T, TgError>>,
    {
        with_read_policy(&self.rate, |e| e.flood_wait_secs(), f).await
    }

    async fn cache_response_peers(&self, users: &[tl::enums::User], chats: &[tl::enums::Chat]) {
        let mut p = self.peers.lock().await;
        cache_peers(&mut p, users, chats);
    }

    async fn resolve_peer(&self, chat_id: i64) -> Result<tl::enums::InputPeer, TgError> {
        {
            let p = self.peers.lock().await;
            if let Some(peer) = p.get(&chat_id) {
                return Ok(peer.clone());
            }
        }
        // Basic chat without -100 prefix:
        if chat_id < 0 && !chat_id.to_string().starts_with("-100") {
            let input = tl::enums::InputPeer::Chat(tl::types::InputPeerChat {
                chat_id: -chat_id,
            });
            let mut p = self.peers.lock().await;
            p.insert(chat_id, input.clone());
            return Ok(input);
        }
        // Warm up cache via fetch_dialogs:
        let _ = self.fetch_dialogs(200).await;
        {
            let p = self.peers.lock().await;
            if let Some(peer) = p.get(&chat_id) {
                return Ok(peer.clone());
            }
        }
        Err(TgError::Local(format!("cannot resolve peer {chat_id}: not found in dialogs")))
    }
}

fn cache_peers(
    peers: &mut HashMap<i64, tl::enums::InputPeer>,
    users: &[tl::enums::User],
    chats: &[tl::enums::Chat],
) {
    for u in users {
        if let tl::enums::User::User(user) = u {
            if let Some(access_hash) = user.access_hash {
                peers.insert(
                    user.id,
                    tl::enums::InputPeer::User(tl::types::InputPeerUser {
                        user_id: user.id,
                        access_hash,
                    }),
                );
            }
        }
    }
    for c in chats {
        match c {
            tl::enums::Chat::Channel(ch) => {
                if let Some(access_hash) = ch.access_hash {
                    let full_id = format!("-100{}", ch.id).parse::<i64>().unwrap_or(-ch.id);
                    let input = tl::enums::InputPeer::Channel(tl::types::InputPeerChannel {
                        channel_id: ch.id,
                        access_hash,
                    });
                    peers.insert(full_id, input.clone());
                    peers.insert(ch.id, input);
                }
            }
            tl::enums::Chat::Chat(chat) => {
                let input = tl::enums::InputPeer::Chat(tl::types::InputPeerChat {
                    chat_id: chat.id,
                });
                peers.insert(-chat.id, input.clone());
                peers.insert(chat.id, input);
            }
            _ => {}
        }
    }
}

/// The container unpacker shared by every raw call: the legacy daemon
/// resolved senders for FREE from the users/chats vectors that ride
/// along in the same response — exactly the pyrogram mechanics.
struct MessageContainer {
    messages: Vec<tl::enums::Message>,
    users: Vec<tl::enums::User>,
    chats: Vec<tl::enums::Chat>,
}

fn unpack_messages(resp: tl::enums::messages::Messages) -> Result<MessageContainer, TgError> {
    match resp {
        tl::enums::messages::Messages::Messages(m) => Ok(MessageContainer {
            messages: m.messages,
            users: m.users,
            chats: m.chats,
        }),
        tl::enums::messages::Messages::Slice(m) => Ok(MessageContainer {
            messages: m.messages,
            users: m.users,
            chats: m.chats,
        }),
        tl::enums::messages::Messages::ChannelMessages(m) => Ok(MessageContainer {
            messages: m.messages,
            users: m.users,
            chats: m.chats,
        }),
        tl::enums::messages::Messages::NotModified(_) => Err(TgError::Local(
            "MTProto returned NotModified for a fresh fetch".into(),
        )),
    }
}

fn peer_display(peer: &tl::enums::Peer) -> Option<i64> {
    match peer {
        tl::enums::Peer::User(u) => Some(u.user_id),
        tl::enums::Peer::Chat(c) => Some(-c.chat_id),
        tl::enums::Peer::Channel(ch) => {
            format!("-100{}", ch.channel_id).parse::<i64>().ok().or(Some(-ch.channel_id))
        }
    }
}

fn user_display(user: &tl::enums::User) -> Option<(i64, String, Option<String>, bool)> {
    if let tl::enums::User::User(u) = user {
        let name = format!(
            "{} {}",
            u.first_name.clone().unwrap_or_default(),
            u.last_name.clone().unwrap_or_default()
        )
        .trim()
        .to_string();
        Some((u.id, name, u.username.clone(), u.bot))
    } else {
        None
    }
}

fn chat_display(chat: &tl::enums::Chat) -> Option<(i64, String, Option<String>, bool)> {
    match chat {
        tl::enums::Chat::Chat(c) => Some((-c.id, c.title.clone(), None, false)),
        tl::enums::Chat::Channel(c) => {
            let id = format!("-100{}", c.id).parse::<i64>().unwrap_or(-c.id);
            Some((
                id,
                c.title.clone(),
                c.username.clone(),
                c.broadcast,
            ))
        }
        _ => None,
    }
}

/// A borrow view over one response container — the lifetime-lean way
/// to resolve senders without fighting partial moves.
struct SenderView<'a> {
    users: &'a [tl::enums::User],
    chats: &'a [tl::enums::Chat],
}

/// Sender line of one message, resolved from the SAME response
/// container (free, one request) — the legacy _fmt_msg mechanics.
fn sender_of(
    raw: &tl::types::Message,
    view: &SenderView,
) -> (String, Option<String>) {
    if let Some(peer) = &raw.from_id {
        if let tl::enums::Peer::User(u) = peer {
            for user in view.users {
                if let Some((id, name, username, _bot)) = user_display(user) {
                    if id == u.user_id {
                        return (name, username);
                    }
                }
            }
            return (format!("user {}", u.user_id), None);
        }
    }
    // Channel posts: the signature first, then the channel title.
    if let Some(author) = &raw.post_author {
        return (author.clone(), None);
    }
    let peer = &raw.peer_id;
    if let Some(id) = peer_display(peer) {
        for c in view.chats {
            if let Some((cid, title, username, _)) = chat_display(c) {
                if cid == id {
                    return (title, username);
                }
            }
        }
    }
    (String::new(), None)
}

fn reply_of(raw: &tl::types::Message) -> Option<i32> {
    match raw.reply_to.as_ref() {
        Some(tl::enums::MessageReplyHeader::Header(h)) => h.reply_to_msg_id,
        _ => None,
    }
}

fn media_kind_of(media: Option<&tl::enums::MessageMedia>) -> MediaKind {
    match media {
        None => MediaKind::None,
        Some(tl::enums::MessageMedia::Photo(_)) => MediaKind::Photo,
        Some(tl::enums::MessageMedia::Geo(_))
        | Some(tl::enums::MessageMedia::GeoLive(_))
        | Some(tl::enums::MessageMedia::Venue(_)) => MediaKind::Location,
        Some(tl::enums::MessageMedia::Poll(p)) => {
            let question = match &p.poll {
                tl::enums::Poll::Poll(poll) => match &poll.question {
                    tl::enums::TextWithEntities::Entities(t) => t.text.clone(),
                },
            };
            MediaKind::Poll(question)
        }
        Some(tl::enums::MessageMedia::Document(doc)) => {
            let document = match &doc.document {
                Some(tl::enums::Document::Document(d)) => d,
                _ => return MediaKind::Document("file".into()),
            };
            let mut name: Option<String> = None;
            let mut sticker: Option<String> = None;
            let mut kind = MediaKind::Document("file".into());
            for attr in &document.attributes {
                match attr {
                    tl::enums::DocumentAttribute::Filename(f) => name = Some(f.file_name.clone()),
                    tl::enums::DocumentAttribute::Sticker(s) => sticker = Some(s.alt.clone()),
                    tl::enums::DocumentAttribute::Animated => {
                        kind = MediaKind::Animation;
                    }
                    tl::enums::DocumentAttribute::Audio(a) => {
                        kind = if a.voice {
                            MediaKind::Voice
                        } else {
                            MediaKind::Audio
                        };
                    }
                    tl::enums::DocumentAttribute::Video(_) => {
                        kind = MediaKind::Video;
                    }
                    _ => {}
                }
            }
            if let Some(emoji) = sticker {
                MediaKind::Sticker(emoji)
            } else if let Some(n) = name {
                if matches!(kind, MediaKind::Document(_)) {
                    kind = MediaKind::Document(n);
                }
                kind
            } else {
                kind
            }
        }
        _ => MediaKind::None,
    }
}

fn raw_message_of(raw: tl::types::Message, view: &SenderView) -> Option<RawMessage> {
    let (sender_name, sender_username) = sender_of(&raw, view);
    Some(RawMessage {
        id: raw.id,
        date: raw.date as i64,
        sender_name,
        sender_username,
        text: raw.message.clone(),
        media: media_kind_of(raw.media.as_ref()),
        reply_to: reply_of(&raw),
    })
}

impl TgApi for GrammersApi {
    async fn get_me(&self) -> Result<UserInfo, TgError> {
        let me = self
            .client
            .get_me()
            .await
            .map_err(|e| TgError::Local(format!("get_me failed: {e}")))?;
        Ok(UserInfo {
            user_id: me.id(),
            username: me.username().unwrap_or_default().to_string(),
            first_name: me.first_name().to_string(),
            last_name: me.last_name().unwrap_or_default().to_string(),
        })
    }

    async fn fetch_dialogs(&self, limit: usize) -> Result<Vec<DialogInfo>, TgError> {
        let resp = self
            .client
            .invoke(&tl::functions::messages::GetDialogs {
                offset_date: 0,
                offset_id: 0,
                offset_peer: tl::enums::InputPeer::Empty,
                limit: limit as i32,
                hash: 0,
                exclude_pinned: false,
                folder_id: None,
            })
            .await
            .map_err(|e| TgError::Local(format!("GetDialogs failed: {e}")))?;

        let (dialogs, users, chats) = match resp {
            tl::enums::messages::Dialogs::Dialogs(d) => (d.dialogs, d.users, d.chats),
            tl::enums::messages::Dialogs::Slice(d) => (d.dialogs, d.users, d.chats),
            _ => return Err(TgError::Local("GetDialogs: unexpected variant".into())),
        };
        self.cache_response_peers(&users, &chats).await;

        let mut out = Vec::new();
        for d in dialogs {
            let tl::enums::Dialog::Dialog(dlg) = d else { continue };
            let Some(peer_id) = peer_display(&dlg.peer) else { continue };
            let (title, username, kind) = match &dlg.peer {
                tl::enums::Peer::User(u) => {
                    let mut title = format!("user {}", u.user_id);
                    let mut username = None;
                    let mut kind = crate::telegram::whitelist::ChatType::Private;
                    for user in &users {
                        if let Some((id, name, uname, bot)) = user_display(user) {
                            if id == u.user_id {
                                title = name;
                                username = uname;
                                if bot {
                                    kind = crate::telegram::whitelist::ChatType::Bot;
                                }
                                break;
                            }
                        }
                    }
                    (title, username, kind)
                }
                tl::enums::Peer::Chat(c) => {
                    let mut title = format!("chat {}", c.chat_id);
                    let mut username = None;
                    for chat in &chats {
                        if let Some((id, name, uname, _)) = chat_display(chat) {
                            if id == -c.chat_id {
                                title = name;
                                username = uname;
                                break;
                            }
                        }
                    }
                    (title, username, crate::telegram::whitelist::ChatType::Group)
                }
                tl::enums::Peer::Channel(ch) => {
                    let mut title = format!("channel {}", ch.channel_id);
                    let mut username = None;
                    let mut kind = crate::telegram::whitelist::ChatType::Channel;
                    for chat in &chats {
                        if let Some((id, name, uname, broadcast)) = chat_display(chat) {
                            if id == peer_id {
                                title = name;
                                username = uname;
                                if !broadcast {
                                    kind = crate::telegram::whitelist::ChatType::Supergroup;
                                }
                                break;
                            }
                        }
                    }
                    (title, username, kind)
                }
            };
            out.push(DialogInfo {
                id: peer_id,
                title,
                kind,
                username,
                unread_count: dlg.unread_count,
            });
        }
        Ok(out)
    }

    async fn fetch_messages(
        &self,
        chat_id: i64,
        limit: u32,
        offset_id: i32,
    ) -> Result<Vec<RawMessage>, TgError> {
        self.policy(|| async {
            let peer = self.resolve_peer(chat_id).await?;
            let resp = self
                .client
                .invoke(&tl::functions::messages::GetHistory {
                    peer,
                    offset_id,
                    offset_date: 0,
                    add_offset: 0,
                    limit: limit as i32,
                    max_id: 0,
                    min_id: 0,
                    hash: 0,
                })
                .await
                .map_err(invocation_err)?;
            let container = unpack_messages(resp)?;
            let MessageContainer { messages, users, chats } = container;
            self.cache_response_peers(&users, &chats).await;
            let view = SenderView { users: &users, chats: &chats };
            Ok(messages
                .into_iter()
                .filter_map(|m| match m {
                    tl::enums::Message::Message(raw) => raw_message_of(raw, &view),
                    _ => None, // MessageService entries carry no text
                })
                .collect())
        })
        .await
    }

    async fn search_messages(
        &self,
        chat_id: i64,
        query: &str,
        limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        self.policy(|| async {
            let peer = self.resolve_peer(chat_id).await?;
            let resp = self
                .client
                .invoke(&tl::functions::messages::Search {
                    peer,
                    q: query.to_string(),
                    from_id: None,
                    saved_peer_id: None,
                    saved_reaction: None,
                    top_msg_id: None,
                    filter: tl::enums::MessagesFilter::InputMessagesFilterEmpty,
                    min_date: 0,
                    max_date: 0,
                    offset_id: 0,
                    add_offset: 0,
                    limit: limit as i32,
                    max_id: 0,
                    min_id: 0,
                    hash: 0,
                })
                .await
                .map_err(invocation_err)?;
            let container = unpack_messages(resp)?;
            let MessageContainer { messages, users, chats } = container;
            self.cache_response_peers(&users, &chats).await;
            let view = SenderView { users: &users, chats: &chats };
            Ok(messages
                .into_iter()
                .filter_map(|m| match m {
                    tl::enums::Message::Message(raw) => raw_message_of(raw, &view),
                    _ => None,
                })
                .collect())
        })
        .await
    }

    async fn search_global(
        &self,
        query: &str,
        limit: u32,
    ) -> Result<Vec<GlobalHit>, TgError> {
        self.policy(|| async {
            let resp = self
                .client
                .invoke(&tl::functions::messages::SearchGlobal {
                    broadcasts_only: false,
                    folder_id: None,
                    q: query.to_string(),
                    filter: tl::enums::MessagesFilter::InputMessagesFilterEmpty,
                    min_date: 0,
                    max_date: 0,
                    offset_rate: 0,
                    offset_peer: tl::enums::InputPeer::Empty,
                    offset_id: 0,
                    limit: limit as i32,
                })
                .await
                .map_err(invocation_err)?;
            let container = unpack_messages(resp)?;
            let MessageContainer { messages, users, chats } = container;
            self.cache_response_peers(&users, &chats).await;
            let view = SenderView { users: &users, chats: &chats };
            let mut hits = Vec::new();
            for m in messages {
                let tl::enums::Message::Message(raw) = m else { continue };
                let peer = &raw.peer_id;
                let id = peer_display(peer).unwrap_or(0);
                let mut title = format!("chat {id}");
                for c in view.chats {
                    if let Some((cid, t, _, _)) = chat_display(c) {
                        if cid == id {
                            title = t;
                            break;
                        }
                    }
                }
                for u in view.users {
                    if let Some((uid, t, _, _)) = user_display(u) {
                        if uid == id {
                            title = t;
                            break;
                        }
                    }
                }
                hits.push(GlobalHit {
                    chat: title,
                    chat_id: id,
                    msg_id: raw.id,
                    date: raw.date,
                    text: crate::telegram::format::clamp_text(&raw.message),
                });
            }
            Ok(hits)
        })
        .await
    }

    async fn get_chat_full(&self, chat_id: i64) -> Result<ChatFull, TgError> {
        let peer = self.resolve_peer(chat_id).await?;
        match peer {
            tl::enums::InputPeer::Channel(ch) => {
                let resp = self
                    .client
                    .invoke(&tl::functions::channels::GetFullChannel {
                        channel: tl::enums::InputChannel::Channel(tl::types::InputChannel {
                            channel_id: ch.channel_id,
                            access_hash: ch.access_hash,
                        }),
                    })
                    .await
                    .map_err(invocation_err)?;
                let full = match resp {
                    tl::enums::messages::ChatFull::Full(f) => f,
                };
                self.cache_response_peers(&full.users, &full.chats).await;
                let (title, username, megagroup) = describe_from_chats(&full.chats, ch.channel_id);
                let (about, members) = match full.full_chat {
                    tl::enums::ChatFull::ChannelFull(f) => (f.about, f.participants_count),
                    _ => (String::new(), None),
                };
                Ok(ChatFull {
                    id: chat_id,
                    title,
                    kind: if megagroup { crate::telegram::whitelist::ChatType::Supergroup } else { crate::telegram::whitelist::ChatType::Channel },
                    username,
                    members,
                    description: about.clone(),
                    bio: about,
                })
            }
            tl::enums::InputPeer::User(u) => {
                let resp = self
                    .client
                    .invoke(&tl::functions::users::GetFullUser {
                        id: tl::enums::InputUser::User(tl::types::InputUser {
                            user_id: u.user_id,
                            access_hash: u.access_hash,
                        }),
                    })
                    .await
                    .map_err(invocation_err)?;
                let full = match resp {
                    tl::enums::users::UserFull::Full(f) => f,
                };
                self.cache_response_peers(&full.users, &full.chats).await;
                let (title, username, _) = describe_from_users(&full.users, u.user_id);
                let about = match full.full_user {
                    tl::enums::UserFull::Full(user_full) => user_full.about.unwrap_or_default(),
                };
                let kind = crate::telegram::whitelist::ChatType::Private;
                Ok(ChatFull {
                    id: chat_id,
                    title,
                    kind,
                    username,
                    members: None,
                    description: about.clone(),
                    bio: about,
                })
            }
            tl::enums::InputPeer::Chat(c) => {
                let resp = self
                    .client
                    .invoke(&tl::functions::messages::GetFullChat { chat_id: c.chat_id })
                    .await
                    .map_err(invocation_err)?;
                let full = match resp {
                    tl::enums::messages::ChatFull::Full(f) => f,
                };
                self.cache_response_peers(&full.users, &full.chats).await;
                let (title, username, _) = describe_from_chats(&full.chats, c.chat_id);
                Ok(ChatFull {
                    id: chat_id,
                    title,
                    kind: crate::telegram::whitelist::ChatType::Group,
                    username,
                    members: None,
                    description: String::new(),
                    bio: String::new(),
                })
            }
            _ => Err(TgError::Local(format!("cannot classify peer {chat_id}"))),
        }
    }

    async fn fetch_discussion_replies(
        &self,
        chat_id: i64,
        message_id: i32,
        limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        self.policy(|| async {
            let peer = self.resolve_peer(chat_id).await?;
            let resp = self
                .client
                .invoke(&tl::functions::messages::GetReplies {
                    peer,
                    msg_id: message_id,
                    offset_id: 0,
                    offset_date: 0,
                    add_offset: 0,
                    limit: limit as i32,
                    max_id: 0,
                    min_id: 0,
                    hash: 0,
                })
                .await
                .map_err(invocation_err)?;
            let container = unpack_messages(resp)?;
            let MessageContainer { messages, users, chats } = container;
            self.cache_response_peers(&users, &chats).await;
            let view = SenderView { users: &users, chats: &chats };
            Ok(messages
                .into_iter()
                .filter_map(|m| match m {
                    tl::enums::Message::Message(raw) => raw_message_of(raw, &view),
                    _ => None,
                })
                .collect())
        })
        .await
    }

    async fn download_media(
        &self,
        chat_id: i64,
        message_id: i32,
        dest: &Path,
    ) -> Result<MediaFile, TgError> {
        let peer = self.resolve_peer(chat_id).await?;
        let packed = match peer {
            tl::enums::InputPeer::User(u) => grammers_session::PackedChat {
                ty: grammers_session::PackedType::User,
                id: u.user_id,
                access_hash: Some(u.access_hash),
            },
            tl::enums::InputPeer::Chat(c) => grammers_session::PackedChat {
                ty: grammers_session::PackedType::Chat,
                id: c.chat_id,
                access_hash: None,
            },
            tl::enums::InputPeer::Channel(ch) => grammers_session::PackedChat {
                ty: grammers_session::PackedType::Broadcast,
                id: ch.channel_id,
                access_hash: Some(ch.access_hash),
            },
            _ => return Err(TgError::Local(format!("cannot convert peer {chat_id} to packed chat"))),
        };
        let msgs = self
            .client
            .get_messages_by_id(packed, &[message_id])
            .await
            .map_err(invocation_err)?;
        let msg = msgs
            .into_iter()
            .flatten()
            .next()
            .ok_or_else(|| TgError::Local(format!("message {message_id} not found")))?;
        if msg.media().is_none() {
            return Err(TgError::Local(format!("message {message_id} has no media")));
        }
        msg.download_media(dest)
            .await
            .map_err(|e| TgError::Local(format!("download failed: {e}")))?;
        let size_bytes = std::fs::metadata(dest)
            .map(|m| m.len())
            .unwrap_or(0);
        Ok(MediaFile {
            file: dest.to_string_lossy().to_string(),
            size_bytes,
        })
    }
}

fn describe_from_chats(
    chats: &[tl::enums::Chat],
    id: i64,
) -> (String, Option<String>, bool) {
    for c in chats {
        if let tl::enums::Chat::Channel(ch) = c {
            if ch.id == id {
                return (ch.title.clone(), ch.username.clone(), ch.megagroup);
            }
        }
        if let tl::enums::Chat::Chat(c) = c {
            if c.id == id {
                return (c.title.clone(), None, false);
            }
        }
    }
    (format!("chat {id}"), None, false)
}

fn describe_from_users(
    users: &[tl::enums::User],
    id: i64,
) -> (String, Option<String>, bool) {
    for u in users {
        if let Some((uid, name, username, bot)) = user_display(u) {
            if uid == id {
                return (name, username, bot);
            }
        }
    }
    (format!("user {id}"), None, false)
}

fn invocation_err(e: grammers_client::InvocationError) -> TgError {
    match e {
        grammers_client::InvocationError::Rpc(rpc) => TgError::Rpc {
            name: rpc.name,
            code: rpc.code,
            value: rpc.value.map(|v| v as i32),
        },
        other => TgError::Local(format!("{other}")),
    }
}

/// The interactive one-time re-login (spec §1.2: the session formats
/// are binary-incompatible, so the switch needs ONE manual login).
/// Runs from ADB: `libminisd.so login --storage <dir>` with
/// TG_API_ID/TG_API_HASH in the environment; phone, code and the
/// optional 2FA password arrive on stdin.
pub async fn run_login(storage: &Path, api_id: i32, api_hash: &str) -> Result<(), String> {
    let session_path = GrammersApi::session_path(storage);
    let session = Session::load_file_or_create(&session_path)
        .map_err(|e| format!("cannot load {}: {e}", session_path.display()))?;
    let client = Client::connect(Config {
        session,
        api_id,
        api_hash: api_hash.to_string(),
        params: InitParams {
            device_model: "minisd".into(),
            system_version: "android".into(),
            app_version: env!("CARGO_PKG_VERSION").into(),
            lang_code: "en".into(),
            ..Default::default()
        },
    })
    .await
    .map_err(|e| format!("MTProto connect failed: {e}"))?;

    if client.is_authorized().await.unwrap_or(false) {
        eprintln!("minisd: session is already authorized, nothing to do");
        return Ok(());
    }

    let phone = prompt_stdin("phone (international format)")?;
    let token = client
        .request_login_code(&phone)
        .await
        .map_err(|e| format!("code request failed: {e}"))?;
    let code = prompt_stdin("login code")?;
    match client.sign_in(&token, &code).await {
        Ok(_) => {}
        Err(grammers_client::SignInError::PasswordRequired(password_token)) => {
            eprintln!(
                "minisd: 2FA password required{}",
                password_token.hint().map(|h| format!(" (hint: {h})")).unwrap_or_default()
            );
            let password = prompt_stdin("2FA password")?;
            client
                .check_password(password_token, password.as_bytes())
                .await
                .map_err(|e| format!("password sign-in failed: {e}"))?;
        }
        Err(e) => return Err(format!("sign-in failed: {e}")),
    }
    client
        .session()
        .save_to_file(&session_path)
        .map_err(|e| format!("failed to save session: {e}"))?;
    eprintln!("minisd: session saved to {}", session_path.display());
    Ok(())
}

fn prompt_stdin(label: &str) -> Result<String, String> {
    eprintln!("minisd login: enter {label}:");
    let mut line = String::new();
    match std::io::stdin().read_line(&mut line) {
        Ok(0) | Err(_) => Err(format!("no {label} on stdin")),
        Ok(_) => Ok(line.trim().to_string()),
    }
}

/// Compile-time witness: the shared Arc form handlers receive.
#[allow(dead_code)]
type SharedApi = Arc<GrammersApi>;

/// `format_sender` is part of this file's contract surface (archive
/// rows carry the rendered sender line).
#[allow(dead_code)]
fn sender_witness() -> fn(&str, Option<&str>) -> String {
    format_sender
}

/// The placeholder transport for the no-client boot path: when the
/// MTProto connect fails (or the smoke run carries no TG_* env), the
/// server still serves initialize/tools/list, and every tool answers
/// the §5.2 session error — dispatch short-circuits on `authorized`
/// before ever touching this, so these bodies are a safety net.
pub struct DeadApi;

impl TgApi for DeadApi {
    async fn get_me(&self) -> Result<UserInfo, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn fetch_dialogs(&self, _limit: usize) -> Result<Vec<DialogInfo>, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn fetch_messages(
        &self,
        _chat_id: i64,
        _limit: u32,
        _offset_id: i32,
    ) -> Result<Vec<RawMessage>, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn search_messages(
        &self,
        _chat_id: i64,
        _query: &str,
        _limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn search_global(
        &self,
        _query: &str,
        _limit: u32,
    ) -> Result<Vec<GlobalHit>, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn get_chat_full(&self, _chat_id: i64) -> Result<ChatFull, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn fetch_discussion_replies(
        &self,
        _chat_id: i64,
        _message_id: i32,
        _limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        Err(TgError::NotAuthorized)
    }
    async fn download_media(
        &self,
        _chat_id: i64,
        _message_id: i32,
        _dest: &Path,
    ) -> Result<MediaFile, TgError> {
        Err(TgError::NotAuthorized)
    }
}

pub enum RuntimeApi {
    Live(GrammersApi),
    Dead(DeadApi),
}

impl TgApi for RuntimeApi {
    async fn get_me(&self) -> Result<UserInfo, TgError> {
        match self {
            Self::Live(a) => a.get_me().await,
            Self::Dead(a) => a.get_me().await,
        }
    }
    async fn fetch_dialogs(&self, limit: usize) -> Result<Vec<DialogInfo>, TgError> {
        match self {
            Self::Live(a) => a.fetch_dialogs(limit).await,
            Self::Dead(a) => a.fetch_dialogs(limit).await,
        }
    }
    async fn fetch_messages(
        &self,
        chat_id: i64,
        limit: u32,
        offset_id: i32,
    ) -> Result<Vec<RawMessage>, TgError> {
        match self {
            Self::Live(a) => a.fetch_messages(chat_id, limit, offset_id).await,
            Self::Dead(a) => a.fetch_messages(chat_id, limit, offset_id).await,
        }
    }
    async fn search_messages(
        &self,
        chat_id: i64,
        query: &str,
        limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        match self {
            Self::Live(a) => a.search_messages(chat_id, query, limit).await,
            Self::Dead(a) => a.search_messages(chat_id, query, limit).await,
        }
    }
    async fn search_global(
        &self,
        query: &str,
        limit: u32,
    ) -> Result<Vec<GlobalHit>, TgError> {
        match self {
            Self::Live(a) => a.search_global(query, limit).await,
            Self::Dead(a) => a.search_global(query, limit).await,
        }
    }
    async fn get_chat_full(&self, chat_id: i64) -> Result<ChatFull, TgError> {
        match self {
            Self::Live(a) => a.get_chat_full(chat_id).await,
            Self::Dead(a) => a.get_chat_full(chat_id).await,
        }
    }
    async fn fetch_discussion_replies(
        &self,
        chat_id: i64,
        message_id: i32,
        limit: u32,
    ) -> Result<Vec<RawMessage>, TgError> {
        match self {
            Self::Live(a) => a.fetch_discussion_replies(chat_id, message_id, limit).await,
            Self::Dead(a) => a.fetch_discussion_replies(chat_id, message_id, limit).await,
        }
    }
    async fn download_media(
        &self,
        chat_id: i64,
        message_id: i32,
        dest: &Path,
    ) -> Result<MediaFile, TgError> {
        match self {
            Self::Live(a) => a.download_media(chat_id, message_id, dest).await,
            Self::Dead(a) => a.download_media(chat_id, message_id, dest).await,
        }
    }
}
