package cn.cloudbuddy.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 品牌图标匹配（照 RikkaHub 的 computeAIIconByName / lobehub 图标集，MIT 资产）。
 * 按模型 ID 或提供商名称/地址猜品牌，返回资产键（如 "deepseek"）；
 * 渲染层把键映射到 drawable。匹配不到返回 null，调用方回落字母头像。
 *
 * 顺序即优先级：先具体品牌后泛匹配（google 排在 gemini/gemma 之后）。
 */
object BrandIcons {

    /** 资产键与匹配模式。资源名固定为 `brand_<key>`，见 [drawableResName]。 */
    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "deepseek" to Regex("deepseek"),
        "qwen" to Regex("qwen|qwq|通义|千问"),
        "kimi" to Regex("kimi"),
        "moonshot" to Regex("moonshot"),
        "doubao" to Regex("doubao|豆包|seedance|seed-oss|火山引擎"),
        "zhipu" to Regex("glm|zhipu|智谱|chatglm"),
        "hunyuan" to Regex("hunyuan|混元"),
        "minimax" to Regex("minimax|abab|海螺"),
        "baichuan" to Regex("baichuan|百川"),
        "yi" to Regex("yi-(lightning|large|medium|small|vl|vision)|^yi-?\\d|零一"),
        "spark" to Regex("spark|星火"),
        "sensenova" to Regex("sensenova|商汤|日日新"),
        "internlm" to Regex("internlm|书生"),
        "stepfun" to Regex("stepfun|step-(1|2|3|r|v|audio|video)|阶跃"),
        "longcat" to Regex("longcat"),
        "mimo" to Regex("mimo|xiaomi|小米"),
        "mistral" to Regex("mistral|mixtral|codestral|ministral|pixtral"),
        "claude" to Regex("claude"),
        "anthropic" to Regex("anthropic"),
        "gemini" to Regex("gemini"),
        "gemma" to Regex("gemma"),
        "google" to Regex("google"),
        "cohere" to Regex("cohere|command-"),
        "perplexity" to Regex("perplexity|sonar"),
        "meta" to Regex("llama|meta-"),
        "xai" to Regex("grok|xai"),
        "openai" to Regex("gpt|openai|chatgpt|davinci|whisper"),
        "groq" to Regex("groq"),
        "ollama" to Regex("ollama"),
        "openrouter" to Regex("openrouter"),
        "siliconflow" to Regex("siliconflow|siliconcloud|硅基流动"),
        "alibabacloud" to Regex("alibaba|阿里云|dashscope|百炼"),
        "nvidia" to Regex("nvidia"),
        "cerebras" to Regex("cerebras"),
        "github" to Regex("github|copilot"),
        "bytedance" to Regex("bytedance"),
    )

    /** 匹配品牌键；[text] 一般是「模型 ID + 提供商名 + 域名」拼起来的串。 */
    fun key(text: String): String? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        return PATTERNS.firstOrNull { it.second.containsMatchIn(t) }?.first
    }

    fun drawableResName(key: String): String = "brand_$key"
}

/**
 * 品牌图标：命中 drawable 就显示图（圆角小方块），否则回落字母头像
 * （首字母 + 按名字哈希取的底色，看多了也能靠颜色认品牌）。
 */
@Composable
fun BrandIcon(name: String, size: Dp, modifier: Modifier = Modifier) {
    val key = remember(name) { BrandIcons.key(name) }
    if (key != null) {
        val res = brandDrawable(key)
        if (res != null) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(size / 4))
            )
            return
        }
    }
    TextAvatar(name = name, size = size, modifier = modifier)
}

@Composable
private fun brandDrawable(key: String): Int? {
    val name = BrandIcons.drawableResName(key)
    val context = LocalContext.current
    return remember(name) {
        runCatching {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) id else null
        }.getOrNull()
    }
}

@Composable
private fun TextAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val letter = name.trim().uppercase().firstOrNull { it.isLetterOrDigit() } ?: "·"
    val bg = remember(letter, name) {
        AVATAR_COLORS[(name.hashCode().let { if (it < 0) it.inv() else it }) % AVATAR_COLORS.size]
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

private val AVATAR_COLORS = listOf(
    Color(0xFF5B8DEF), Color(0xFF34A67A), Color(0xFFE8853D), Color(0xFF9A6BD6),
    Color(0xFFD65B7A), Color(0xFF3FB3C4), Color(0xFFC25B4A), Color(0xFF7A8B3D),
)

/**
 * 提供商站点图标（照 RikkaHub 的 Favicon：favicone.com 按 host 取站点的真实 logo），
 * 网络失败或超时就回落 [BrandIcon]。结果按 host 记忆，列表滚动不重复下载。
 */
@Composable
fun ProviderIcon(providerName: String, baseUrl: String, size: Dp, modifier: Modifier = Modifier) {
    val host = remember(baseUrl) {
        runCatching { java.net.URI(baseUrl.trim()).host } .getOrNull()
    }
    var favicon by remember(host) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(host) {
        if (host.isNullOrBlank()) return@LaunchedEffect
        favicon = FaviconCache.get(host)
        if (favicon == null) {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    URL("https://favicone.com/$host?s=96")
                        .openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.takeIf { it.width > 1 } // favicone 未命中时回 1×1 占位图
                    ?.asImageBitmap()
            }
            if (bmp != null) FaviconCache.put(host, bmp)
            favicon = bmp
        }
    }
    val bmp = favicon
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(RoundedCornerShape(size / 4))
        )
    } else {
        BrandIcon(name = "$providerName $baseUrl", size = size, modifier = modifier)
    }
}

private object FaviconCache {
    private val map = ConcurrentHashMap<String, ImageBitmap>()
    fun get(host: String): ImageBitmap? = map[host]
    fun put(host: String, bmp: ImageBitmap) {
        if (map.size > 64) map.clear()
        map[host] = bmp
    }
}
