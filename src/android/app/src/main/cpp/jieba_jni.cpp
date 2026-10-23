// [T-android-text-segmenter] JNI bridge to CppJieba for TextSegmenter's Chinese
// path. Loads the dictionary trie + HMM model once from a directory the Kotlin
// side extracted out of Assets (JNI can't read Assets by path), then serves
// exact-mode (`Cut`) and search-mode (`CutForSearch`) segmentation.
//
// We deliberately build DictTrie + HMMModel directly and share them between a
// MixSegment (exact) and a QuerySegment (search) instead of using the full
// cppjieba::Jieba facade — Jieba pulls in KeywordExtractor, which needs
// idf.utf8 + stop_words.utf8 (~6MB we don't ship). Segmentation only needs the
// dict + HMM model, so this keeps the shipped asset down to ~5.4MB.
//
// Thread-safety: a single global instance guarded by a std::mutex. All C++ is
// wrapped in try/catch so a load failure or a malformed input returns false /
// an empty array to Kotlin rather than crashing the app.

#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cppjieba/DictTrie.hpp"
#include "cppjieba/HMMModel.hpp"
#include "cppjieba/MixSegment.hpp"
#include "cppjieba/PosTagger.hpp"
#include "cppjieba/QuerySegment.hpp"

#define LOG_TAG "JiebaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Owns the loaded dictionaries and both segmenters. Constructed once, under the
// global mutex, on the first successful nativeInit call.
struct JiebaState {
    std::unique_ptr<cppjieba::DictTrie> dict;
    std::unique_ptr<cppjieba::HMMModel> hmm;
    std::unique_ptr<cppjieba::MixSegment> mixSeg;      // exact mode (Cut, HMM on)
    std::unique_ptr<cppjieba::QuerySegment> querySeg;  // search-engine mode
};

std::mutex g_mutex;
std::unique_ptr<JiebaState> g_state;

// Join two path components with a single '/'.
std::string joinPath(const std::string& dir, const std::string& file) {
    if (dir.empty()) return file;
    if (dir.back() == '/') return dir + file;
    return dir + "/" + file;
}

// Append one code point to a std::string as UTF-8.
void appendUtf8(std::string& out, uint32_t cp) {
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// Convert a jstring to a std::string in standard UTF-8.
//
// [T-jieba-utf8] This must NOT use GetStringUTFChars. That returns Modified
// UTF-8, in which a character outside the BMP is a surrogate pair written as
// two 3-byte sequences, while cppjieba reads standard UTF-8 where the same
// character is one 4-byte sequence. The engine therefore saw corrupt input and
// echoed back bytes that no longer round-tripped. Decoding the jchar units by
// hand gives cppjieba the encoding it expects, and a lone surrogate degrades
// to U+FFFD instead of poisoning the whole string.
std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const jsize len = env->GetStringLength(s);
    if (len == 0) return std::string();
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            } else {
                cp = 0xFFFD;  // high surrogate not followed by a low one
            }
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;      // stray low surrogate
        }
        appendUtf8(out, cp);
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

// Build a Java String from a standard-UTF-8 std::string.
//
// [T-jieba-utf8] NewStringUTF must NOT be used here. It requires Modified
// UTF-8, which by definition cannot express characters outside the BMP, and
// cppjieba hands back standard UTF-8 bytes. Any 4-byte sequence therefore
// aborted the entire process once CheckJNI was active - it is enabled for
// every debuggable build - with "input is not valid Modified UTF-8: illegal
// continuation byte". Two independent crashes on this tree carried the same
// filename. NewString takes UTF-16 code units and imposes no such constraint,
// so the conversion happens here instead.
jstring toJavaString(JNIEnv* env, const std::string& utf8) {
    std::vector<jchar> units;
    units.reserve(utf8.size());
    size_t i = 0;
    const size_t n = utf8.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(utf8[i]);
        uint32_t cp;
        size_t extra;
        if (c < 0x80) {
            cp = c; extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1Fu; extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0Fu; extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07u; extra = 3;
        } else {
            units.push_back(0xFFFD);  // stray continuation or invalid lead
            ++i;
            continue;
        }
        if (i + extra >= n) {
            units.push_back(0xFFFD);
            break;
        }
        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            const unsigned char cc = static_cast<unsigned char>(utf8[i + k]);
            if ((cc & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!ok) {
            units.push_back(0xFFFD);
            ++i;
            continue;
        }
        i += extra + 1;
        if (cp > 0x10FFFF) {
            units.push_back(0xFFFD);
        } else if (cp >= 0x10000) {
            cp -= 0x10000;
            units.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            units.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            units.push_back(static_cast<jchar>(cp));
        }
    }
    if (units.empty()) return env->NewStringUTF("");
    return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

// Build a Java String[] from a vector of UTF-8 words.
jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& words) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(words.size()), stringClass, nullptr);
    if (arr == nullptr) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(words.size()); ++i) {
        jstring w = toJavaString(env, words[i]);
        env->SetObjectArrayElement(arr, i, w);
        env->DeleteLocalRef(w);
    }
    return arr;
}

// Empty String[0] — the failure / empty-input return value.
jobjectArray emptyStringArray(JNIEnv* env) {
    jclass stringClass = env->FindClass("java/lang/String");
    return env->NewObjectArray(0, stringClass, nullptr);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring dictDir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state) return JNI_TRUE;  // already initialized

    const std::string dir = toStdString(env, dictDir);
    if (dir.empty()) {
        LOGE("nativeInit: empty dict dir");
        return JNI_FALSE;
    }

    try {
        auto state = std::make_unique<JiebaState>();
        const std::string dictPath = joinPath(dir, "jieba.dict.utf8");
        const std::string hmmPath = joinPath(dir, "hmm_model.utf8");

        state->dict = std::make_unique<cppjieba::DictTrie>(dictPath);
        state->hmm = std::make_unique<cppjieba::HMMModel>(hmmPath);
        // Both segmenters borrow the same dict/HMM (non-owning pointers).
        state->mixSeg = std::make_unique<cppjieba::MixSegment>(state->dict.get(), state->hmm.get());
        state->querySeg = std::make_unique<cppjieba::QuerySegment>(state->dict.get(), state->hmm.get());

        g_state = std::move(state);
        LOGI("nativeInit: loaded dictionaries from %s", dir.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("nativeInit failed: %s", e.what());
        g_state.reset();
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeInit failed: unknown error");
        g_state.reset();
        return JNI_FALSE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeSegment(
    JNIEnv* env, jobject /* thiz */, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state) {
        LOGE("nativeSegment before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::string> words;
        // Exact mode with HMM enabled (recovers OOV words).
        g_state->mixSeg->Cut(sentence, words, /* hmm = */ true);
        return toStringArray(env, words);
    } catch (const std::exception& e) {
        LOGE("nativeSegment failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativeSegment failed: unknown error");
        return emptyStringArray(env);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeSegmentForSearch(
    JNIEnv* env, jobject /* thiz */, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state) {
        LOGE("nativeSegmentForSearch before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::string> words;
        g_state->querySeg->Cut(sentence, words, /* hmm = */ true);
        return toStringArray(env, words);
    } catch (const std::exception& e) {
        LOGE("nativeSegmentForSearch failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativeSegmentForSearch failed: unknown error");
        return emptyStringArray(env);
    }
}

// [T-android-voice-correction] Part-of-speech tagging, needed by
// VocabularyFilter's admission scoring (proper-noun tags nr/ns/nz/nt earn +2).
//
// Returns a FLAT String[] of alternating token, tag, token, tag, … rather than
// an array of pairs: building a Pair[] here would mean FindClass +
// GetMethodID + NewObject per token across the JNI boundary, where the flat
// form is one toJavaString per element and a trivial pairwise walk in Kotlin.
//
// PosTagger is stateless and cheap to construct (its ctor is empty; it borrows
// the DictTrie from the SegmentTagged we hand it), so unlike KeywordExtractor
// it needs no extra dictionary files and is built per call rather than kept in
// JiebaState.
JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativePosTag(
    JNIEnv* env, jobject /* thiz */, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_state) {
        LOGE("nativePosTag before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::pair<std::string, std::string> > tagged;
        cppjieba::PosTagger tagger;
        // MixSegment is a SegmentTagged, so it supplies both the cut and the
        // DictTrie the tag lookup needs.
        tagger.Tag(sentence, tagged, *g_state->mixSeg);
        std::vector<std::string> flat;
        flat.reserve(tagged.size() * 2);
        for (size_t i = 0; i < tagged.size(); ++i) {
            flat.push_back(tagged[i].first);
            flat.push_back(tagged[i].second);
        }
        return toStringArray(env, flat);
    } catch (const std::exception& e) {
        LOGE("nativePosTag failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativePosTag failed: unknown error");
        return emptyStringArray(env);
    }
}

}  // extern "C"
