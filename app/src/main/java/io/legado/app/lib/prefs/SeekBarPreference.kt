package io.legado.app.lib.prefs

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.utils.progressAdd

open class SeekBarPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {
    private var mMinValue = 0
    private var mMaxValue = 1000

    private var mSeekBar: SeekBar? = null
    private var mValueText: TextView? = null

    private var seekPlus: ImageView? = null
    private var seekReduce: ImageView? = null

    var value: Int = 0
        set(value) {
            field = value.coerceIn(mMinValue, mMaxValue)
            persistInt(field)
            // SeekBar 的 progress 从 0 起算，而 value 带最小值，
            // 因此必须减去 mMinValue。MinValue 非 0 的滑块（如底栏胶囊宽度）
            // 少了这一步会导致松手/点按 +/- 后滑块跳位。
            mSeekBar?.progress = field - mMinValue
            mValueText?.text = field.toString()
        }

    init {
        layoutResource = R.layout.view_preference_seekbar
        val a = context.obtainStyledAttributes(attrs, R.styleable.NumberPickerPreference)
        mMinValue = a.getInt(R.styleable.NumberPickerPreference_MinValue, mMinValue)
        mMaxValue = a.getInt(R.styleable.NumberPickerPreference_MaxValue, mMaxValue)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        mSeekBar = holder.findViewById(R.id.seek_bar) as? SeekBar
        mValueText = holder.findViewById(R.id.tv_seek_value) as? TextView
        seekPlus = holder.findViewById(R.id.iv_seek_plus) as? ImageView
        seekReduce = holder.findViewById(R.id.iv_seek_reduce) as? ImageView
        (holder.findViewById(R.id.preference_title) as? TextView)?.text = title
        (holder.findViewById(R.id.preference_desc) as? TextView)?.text = summary
        mSeekBar?.apply {
            max = mMaxValue - mMinValue
            progress = value - mMinValue

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val newValue = progress + mMinValue
                    mValueText?.text = newValue.toString()
                    if (fromUser) {
                        value = newValue
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val newValue = seekBar.progress + mMinValue
                    if (callChangeListener(newValue)) {
                        value = newValue
                    }
                }
            })
        }
        mValueText?.text = value.toString()
        seekPlus?.setOnClickListener {
            mSeekBar?.progressAdd(1)
            value++
        }
        seekReduce?.setOnClickListener {
            mSeekBar?.progressAdd(-1)
            value--
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 500)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedInt((defaultValue as? Int) ?: 0)
    }


}
