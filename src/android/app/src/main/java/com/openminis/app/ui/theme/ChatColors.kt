package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// iOS reference:
//   systemBackground        -> background
//   secondarySystemBackground -> secondaryBg
//   tertiarySystemFill      -> userBubble
//   tertiarySystemGroupedBackground -> toolBg
//   label                   -> primaryText
//   secondaryLabel          -> secondaryText
//   tertiaryLabel           -> tertiaryText
//   quaternaryLabel         -> sendButtonDisabled
//   separator               -> border
//   systemGray6             -> inlineCodeBg / toolCapsuleBg
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color.White,
    secondaryBg = Color(0xFFF2F2F7),
    inputBg = Color.White,
    inputIconBg = Color(0xFFF2F2F7),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0x4D3C3C43),
    primaryText = Color(0xFF000000),
    secondaryText = Color(0x993C3C43),
    tertiaryText = Color(0x4D3C3C43),
    disabledText = Color(0x2E3C3C43),
    userBubble = Color(0x1E787880),
    toolBg = Color(0xFFF2F2F7),
    toolBorder = Color(0x14000000),
    toolCapsuleBg = Color(0xFFF2F2F7),
    separator = Color(0x4D3C3C43),
    sendButton = Color(0xFF000000),
    sendButtonDisabled = Color(0x2E3C3C43),
    codeBlockBg = Color(0xFF000000),
    codeBlockText = Color(0xFF34C759),
    inlineCodeBg = Color(0xFFF2F2F7),
    inlineCodeText = Color(0xFFFF9500),
    link = Color(0xFF007AFF),
    blockquoteBar = Color(0x80FF9500),
    thinking = Color(0xFF007AFF),
    warningBg = Color(0x14FF9500),
    warningText = Color(0x73000000),
    tableBorder = Color(0x1F000000),
    inputShadow = Color.Transparent,
    toastBg = Color(0x2E007AFF),
    thumbnailBorder = Color(0x33808080),
    sheetHeaderBg = Color(0xFFFFFFFF),
    sheetHeaderBorder = Color(0x1A000000),
    fabAccent = Color(0xFFB7AF96),
)

// Grok-style OLED-black dark palette. Background is pure #000 (AMOLED),
// surfaces sit in a light ramp (#1C1C1E → #2C2C2C) so layers separate on
// black. Text is pure white, accent/link is a soft blue.
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF000000),
    secondaryBg = Color(0xFF1C1C1E),
    inputBg = Color(0xFF1C1C1E),
    inputIconBg = Color(0xFF242424),
    inputIconBorder = Color(0xFF3A3A3A),
    inputBorder = Color(0x40545458),
    primaryText = Color(0xFFFFFFFF),
    secondaryText = Color(0x99EBEBF5),
    tertiaryText = Color(0x4DEBEBF5),
    disabledText = Color(0x2EEBEBF5),
    userBubble = Color(0xFF1C1C1E),
    toolBg = Color(0xFF1C1C1E),
    toolBorder = Color(0x40545458),
    toolCapsuleBg = Color(0xFF242424),
    separator = Color(0x66545458),
    sendButton = Color(0xFFFFFFFF),
    sendButtonDisabled = Color(0x2EEBEBF5),
    codeBlockBg = Color(0xFF0A0A0A),
    codeBlockText = Color(0xFF8CF38C),
    inlineCodeBg = Color(0xFF1C1C1E),
    inlineCodeText = Color(0xFFFF9F0A),
    link = Color(0xFF6EA8FE),
    blockquoteBar = Color(0x80FF9F0A),
    thinking = Color(0xFF6EA8FE),
    warningBg = Color(0x14FF9F0A),
    warningText = Color(0x73FFFFFF),
    tableBorder = Color(0xFF2C2C2E),
    inputShadow = Color(0x80000000),
    toastBg = Color(0x2E6EA8FE),
    thumbnailBorder = Color(0x20545458),
    sheetHeaderBg = Color(0xFF1C1C1E),
    sheetHeaderBorder = Color(0x33FFFFFF),
    fabAccent = Color(0xFFE8E8E8),
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
