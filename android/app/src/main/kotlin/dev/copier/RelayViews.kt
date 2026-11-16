package dev.copier

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

/**
 * The relay screen (docs/superpowers/specs/2026-11-16-relay-interface-design.md): the Mac's items on
 * the left, this phone's on the right, joined by a rail with one dot per item (filled = from the
 * Mac, hollow = from this phone). Platform views only; the screen is rebuilt on each render, so
 * thumbnails are cached here.
 */
class RelayViews(private val activity: Activity) {
    class Actions(
        val copy: (ActivityItem) -> Unit,
        val open: (ActivityItem) -> Unit,
        val share: (ActivityItem) -> Unit,
        val sendAgain: (ActivityItem) -> Unit,
        val remove: (ActivityItem) -> Unit,
    )

    private val accent = color(R.color.accent)
    private val onAccent = color(R.color.on_accent)
    private val outgoingFill = color(R.color.outgoing_tint)
    private val incomingFill = color(R.color.incoming_fill)
    private val hairline = color(R.color.hairline)
    private val delivered = color(R.color.delivered)
    private val failed = color(R.color.failed)
    private val idleDot = color(R.color.idle_dot)
    private val secondary = themeColor(android.R.attr.textColorSecondary)
    private val windowBackground = themeColor(android.R.attr.colorBackground)
    private val bubbleMax = ((activity.resources.displayMetrics.widthPixels - dp(48 + 34)) * 0.86f).toInt()
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun header(macName: String, status: String, connected: Boolean): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(incomingFill, dp(22))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(TextView(activity).apply {
                text = macName
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(
                    View(activity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (connected) delivered else idleDot)
                        }
                    },
                    LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) },
                )
                addView(TextView(activity).apply {
                    text = status
                    setTextColor(secondary)
                })
            })
        }

    fun actionRow(onPhotos: () -> Unit, onFiles: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(4))
            addView(pill("Send photos", filled = true, onPhotos), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
            addView(pill("Send files", filled = false, onFiles), LinearLayout.LayoutParams(0, dp(48), 1f))
        }

    fun empty(macName: String): View =
        TextView(activity).apply {
            text = "Copy something on $macName, or tap Send photos."
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(40))
        }

    fun timeline(items: List<ActivityItem>, thumbFile: (String) -> File, canResend: (ActivityItem) -> Boolean, actions: Actions): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            items.forEachIndexed { index, item ->
                addView(row(item, first = index == 0, last = index == items.lastIndex, thumbFile(item.id), canResend(item), actions))
            }
        }

    private fun row(item: ActivityItem, first: Boolean, last: Boolean, thumb: File, canResend: Boolean, actions: Actions): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            RailView(activity, accent, windowBackground, filled = item.fromMac, first, last, dotY = dp(18).toFloat()),
            LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val column = FrameLayout(activity).apply { setPadding(dp(6), 0, 0, dp(16)) }
        column.addView(
            bubble(item, thumb, canResend, actions),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (item.fromMac) Gravity.START else Gravity.END),
        )
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Bubbles differ by content: a photo, a quote block, or a file row. */
    private fun bubble(item: ActivityItem, thumb: File, canResend: Boolean, actions: Actions): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val textual = item.kind == ActivityItem.Kind.TEXT || item.kind == ActivityItem.Kind.LINK
        val bitmap = if (textual) null else thumbnail(item.id, thumb)
        when {
            bitmap != null || item.kind == ActivityItem.Kind.IMAGE -> photo(box, item, bitmap, actions)
            textual -> quote(box, item)
            else -> file(box, item)
        }
        footer(box, item, canResend, actions)
        box.setOnLongClickListener {
            actions.remove(item)
            true
        }
        return box
    }

    private fun photo(box: LinearLayout, item: ActivityItem, bitmap: Bitmap?, actions: Actions) {
        val width = (bubbleMax * 0.8f).toInt()
        val height = if (bitmap != null) (width * bitmap.height / bitmap.width.toFloat()).toInt().coerceIn(dp(90), dp(280)) else dp(140)
        box.addView(
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = roundOutline(dp(16))
                contentDescription = item.title
                if (bitmap != null) setImageBitmap(bitmap) else background = rounded(incomingFill, dp(16))
                if (item.fromMac && item.uri != null) setOnClickListener { actions.open(item) }
            },
            LinearLayout.LayoutParams(width, height),
        )
        box.addView(TextView(activity).apply {
            text = titleWithSize(item)
            maxWidth = width
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(2), dp(6), 0, 0)
        })
    }

    private fun quote(box: LinearLayout, item: ActivityItem) {
        box.background = rounded(if (item.fromMac) incomingFill else outgoingFill, dp(18))
        box.setPadding(dp(14), dp(10), dp(14), dp(8))
        box.addView(TextView(activity).apply {
            maxWidth = bubbleMax - dp(28)
            textSize = 15f
            when {
                item.sensitive -> {
                    text = "Password, hidden"
                    setTextColor(secondary)
                    setTypeface(typeface, Typeface.ITALIC)
                }
                item.kind == ActivityItem.Kind.LINK -> {
                    text = item.text ?: item.title
                    setTextColor(accent)
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                }
                else -> {
                    text = item.text ?: item.title
                    maxLines = 6
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
        })
    }

    private fun file(box: LinearLayout, item: ActivityItem) {
        box.background = rounded(if (item.fromMac) incomingFill else outgoingFill, dp(12)).apply { setStroke(dp(1), hairline) }
        box.setPadding(dp(12), dp(10), dp(14), dp(8))
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_file)
                    imageTintList = ColorStateList.valueOf(accent)
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) },
            )
            addView(TextView(activity).apply {
                text = titleWithSize(item)
                maxWidth = bubbleMax - dp(64)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        })
    }

    private fun footer(box: LinearLayout, item: ActivityItem, canResend: Boolean, actions: Actions) {
        if (item.state == ActivityItem.State.TRANSFERRING) {
            box.addView(
                ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (item.progress * 1000).toInt()
                    progressTintList = ColorStateList.valueOf(accent)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) },
            )
        }
        val stateColor = when (item.state) {
            ActivityItem.State.DELIVERED -> delivered
            ActivityItem.State.FAILED -> failed
            ActivityItem.State.TRANSFERRING -> accent
            else -> secondary
        }
        box.addView(TextView(activity).apply {
            text = SpannableStringBuilder()
                .append(item.stateLine(), ForegroundColorSpan(stateColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append("   ")
                .append(time(item.time), ForegroundColorSpan(secondary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            textSize = 12.5f
            fontFeatureSettings = "tnum"
            setPadding(0, dp(6), 0, 0)
        })
        val buttons = buildList {
            val textual = item.kind == ActivityItem.Kind.TEXT || item.kind == ActivityItem.Kind.LINK
            if (item.kind == ActivityItem.Kind.LINK && item.text != null) add("Open" to { actions.open(item) })
            if (textual && item.text != null && !item.sensitive) add("Copy" to { actions.copy(item) })
            if (!textual && item.fromMac && item.uri != null && item.state == ActivityItem.State.RECEIVED) {
                add("Open" to { actions.open(item) })
                add("Share" to { actions.share(item) })
            }
            if (!item.fromMac && item.state == ActivityItem.State.FAILED && canResend) add("Send again" to { actions.sendAgain(item) })
        }
        if (buttons.isNotEmpty()) {
            box.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, action) in buttons) addView(textButton(label, action))
            })
        }
    }

    private fun titleWithSize(item: ActivityItem): CharSequence {
        val size = item.size?.let { Formatter.formatShortFileSize(activity, it) } ?: return item.title
        return SpannableStringBuilder(item.title).append("  ").append(size, ForegroundColorSpan(secondary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun time(millis: Long): String =
        DateUtils.formatDateTime(
            activity, millis,
            if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )

    private fun thumbnail(id: String, file: File): Bitmap? {
        thumbnails.get(id)?.let { return it }
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.path)?.also { thumbnails.put(id, it) }
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) =
        Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            setTextColor(if (filled) onAccent else accent)
            val shape = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                if (filled) setColor(accent) else setStroke(dp(1), accent)
            }
            background = RippleDrawable(ColorStateList.valueOf(if (filled) onAccent and 0x33FFFFFF else accent and 0x33FFFFFF), shape, null)
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit) =
        Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            setTextColor(accent)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }

    private fun roundOutline(radius: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
    }

    private fun color(id: Int) = activity.resources.getColor(id, activity.theme)

    private fun themeColor(attr: Int): Int {
        val values = activity.obtainStyledAttributes(intArrayOf(attr))
        return try {
            values.getColor(0, 0xFF808080.toInt())
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

/** The connecting line through the timeline, with this row's dot. */
private class RailView(
    context: Context,
    accent: Int,
    background: Int,
    private val filled: Boolean,
    private val first: Boolean,
    private val last: Boolean,
    private val dotY: Float,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        alpha = 90
        strokeWidth = 2 * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (filled) accent else background }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        canvas.drawLine(x, if (first) dotY else 0f, x, if (last) dotY else height.toFloat(), line)
        val radius = 5 * density
        canvas.drawCircle(x, dotY, radius, dot)
        if (!filled) canvas.drawCircle(x, dotY, radius, ring)
    }
}
