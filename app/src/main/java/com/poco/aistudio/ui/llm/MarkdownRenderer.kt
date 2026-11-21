package com.poco.aistudio.ui.llm

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poco.aistudio.ui.theme.ElectricCyan
import com.poco.aistudio.ui.theme.EmeraldGreen
import com.poco.aistudio.ui.theme.PocoYellow
import com.poco.aistudio.ui.theme.SurfaceDark
import com.poco.aistudio.ui.theme.SurfaceElevated
import com.poco.aistudio.ui.theme.SurfaceOutline
import com.poco.aistudio.ui.theme.TextPrimary
import com.poco.aistudio.ui.theme.TextSecondary

private sealed interface MdSegment {
    data class ThinkBlock(val content: String, val isClosed: Boolean) : MdSegment
    data class CodeBlock(val language: String, val code: String) : MdSegment
    data class Prose(val text: String) : MdSegment
}

private fun parseMessageSegments(raw: String): List<MdSegment> {
    val result = mutableListOf<MdSegment>()
    var remaining = raw

    // 1. Extract <think>...</think> (handles streaming unclosed <think> cleanly)
    while (remaining.contains("<think>")) {
        val startIdx = remaining.indexOf("<think>")
        if (startIdx > 0) {
            result.addAll(parseCodeAndProse(remaining.substring(0, startIdx)))
        }
        val afterOpen = remaining.substring(startIdx + "<think>".length)
        val endIdx = afterOpen.indexOf("</think>")
        if (endIdx >= 0) {
            result.add(MdSegment.ThinkBlock(afterOpen.substring(0, endIdx).trim(), isClosed = true))
            remaining = afterOpen.substring(endIdx + "</think>".length)
        } else {
            result.add(MdSegment.ThinkBlock(afterOpen.trim(), isClosed = false))
            remaining = ""
        }
    }
    if (remaining.isNotEmpty()) {
        result.addAll(parseCodeAndProse(remaining))
    }
    return result
}

private fun parseCodeAndProse(text: String): List<MdSegment> {
    val segments = mutableListOf<MdSegment>()
    val regex = Regex("```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)(```|$)")
    var lastIndex = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > lastIndex) {
            val prose = text.substring(lastIndex, match.range.first).trim()
            if (prose.isNotEmpty()) segments.add(MdSegment.Prose(prose))
        }
        val lang = match.groupValues[1].ifBlank { "code" }
        val code = match.groupValues[2].trimEnd()
        segments.add(MdSegment.CodeBlock(lang, code))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val tail = text.substring(lastIndex).trim()
        if (tail.isNotEmpty()) segments.add(MdSegment.Prose(tail))
    }
    return segments
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when",
    "for", "while", "import", "package", "private", "public", "suspend", "override",
    "fn", "let", "mut", "pub", "struct", "enum", "impl", "match", "use", "mod", "async", "await",
    "def", "from", "try", "except", "with", "as", "lambda", "True", "False", "None",
    "const", "function", "export", "default", "int", "float", "void", "bool", "char"
)

private fun highlightCode(code: String): AnnotatedString = buildAnnotatedString {
    val tokenRegex = Regex("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b[A-Za-z_][A-Za-z0-9_]*\\b|\\b\\d+(?:\\.\\d+)?\\b)")
    var idx = 0
    for (m in tokenRegex.findAll(code)) {
        if (m.range.first > idx) {
            append(code.substring(idx, m.range.first))
        }
        val tok = m.value
        val color = when {
            tok.startsWith("//") || tok.startsWith("#") -> Color(0xFF6E7681)
            tok.startsWith("\"") || tok.startsWith("'") -> EmeraldGreen
            tok in CODE_KEYWORDS -> PocoYellow
            tok.first().isDigit() -> Color(0xFFFF9E64)
            tok.first().isUpperCase() -> ElectricCyan
            else -> TextPrimary
        }
        withStyle(SpanStyle(color = color)) {
            append(tok)
        }
        idx = m.range.last + 1
    }
    if (idx < code.length) {
        append(code.substring(idx))
    }
}

private fun formatInlineMarkdown(prose: String): AnnotatedString = buildAnnotatedString {
    val lines = prose.lines()
    lines.forEachIndexed { lineIdx, rawLine ->
        val trimmed = rawLine.trimStart()
        when {
            trimmed.startsWith("### ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = PocoYellow)) {
                    append(trimmed.removePrefix("### "))
                }
            }
            trimmed.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = PocoYellow)) {
                    append(trimmed.removePrefix("## "))
                }
            }
            trimmed.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = PocoYellow)) {
                    append(trimmed.removePrefix("# "))
                }
            }
            else -> {
                val pattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
                var pos = 0
                for (m in pattern.findAll(rawLine)) {
                    if (m.range.first > pos) append(rawLine.substring(pos, m.range.first))
                    val v = m.value
                    if (v.startsWith("**")) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary)) {
                            append(v.removeSurrounding("**"))
                        }
                    } else if (v.startsWith("`")) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = ElectricCyan,
                                background = Color(0xFF1A1A26)
                            )
                        ) {
                            append(" ${v.removeSurrounding("`")} ")
                        }
                    }
                    pos = m.range.last + 1
                }
                if (pos < rawLine.length) append(rawLine.substring(pos))
            }
        }
        if (lineIdx < lines.lastIndex) append("\n")
    }
}

@Composable
fun MarkdownMessageContent(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { parseMessageSegments(text) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MdSegment.ThinkBlock -> {
                    var expanded by remember { mutableStateOf(!seg.isClosed) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF11111A))
                            .border(1.dp, SurfaceOutline, RoundedCornerShape(10.dp))
                            .clickable { expanded = !expanded }
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (seg.isClosed) "Размышления (<think>)" else "Модель думает...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ElectricCyan
                                )
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            Text(
                                text = seg.content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                ),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                is MdSegment.CodeBlock -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .border(1.dp, SurfaceOutline, RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceElevated)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = seg.language.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = PocoYellow
                            )
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        clipboard.setText(AnnotatedString(seg.code))
                                        Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Копировать",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Копировать",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ElectricCyan
                                )
                            }
                        }
                        Text(
                            text = highlightCode(seg.code),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }

                is MdSegment.Prose -> {
                    Text(
                        text = formatInlineMarkdown(seg.text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(1.dp))
    }
}
