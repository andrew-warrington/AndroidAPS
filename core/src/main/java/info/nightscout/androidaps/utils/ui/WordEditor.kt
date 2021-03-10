package info.nightscout.androidaps.utils.ui

import android.content.Context
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.widget.EditText
import android.widget.LinearLayout
import info.nightscout.androidaps.core.R
import java.util.concurrent.ScheduledExecutorService

class WordEditor : LinearLayout {

    var editText: EditText? = null
    var value: String? = null
    var allowSpaces: Boolean? = null
    var allowNumbers: Boolean? = null
    var allowText: Boolean? = null
    var allowNonAlphaNum: Boolean? = null
    var textWatcher: TextWatcher? = null
    //var okButton: Button? = null
    protected var focused = false
    private val mHandler: Handler? = null
    private val mUpdater: ScheduledExecutorService? = null

    constructor(context: Context?) : super(context, null) {
        initialize(context)
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        initialize(context)
    }

    protected fun inflate(context: Context?) {
        LayoutInflater.from(context).inflate(R.layout.word_editor_layout, this, true)
    }

    protected fun initialize(context: Context?) {
        // set layout view
        inflate(context)

        // init ui components
        editText = findViewById(R.id.word)
        editText?.setId(generateViewId())
        setTextWatcher(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (focused) value = editText?.getText().toString()
                /*
                if (okButton != null) {
                    if (value === "" || value == null) okButton!!.visibility = INVISIBLE else okButton!!.visibility = VISIBLE
                }

                 */
            }
        })
        editText?.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            focused = hasFocus
            if (!focused) checkValue() // check appropriate input
            editText!!.setText(value)
        })
        editText?.setOnFocusChangeListener(OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            focused = hasFocus
            if (!focused) checkValue() // check appropriate input
            editText!!.setText(value)
        })
    }

    private fun checkValue() {
        //TODO make this function
        //check we have met the requirements re: allowSpaces etc.
        return
    }

    @JvmName("setTextWatcher1") fun setTextWatcher(textWatcher: TextWatcher?) {
        this.textWatcher = textWatcher
        editText!!.addTextChangedListener(textWatcher)
        editText!!.onFocusChangeListener = OnFocusChangeListener { v: View?, hasFocus: Boolean ->
            if (!hasFocus) {
                value = editText!!.text.toString()
                if (value == null) {
                    value = ""
                    editText!!.setText(value)
                    // if (okButton != null) okButton!!.visibility = INVISIBLE
                }
            }
        }
    }

    fun setParams(initValue: String? = "", allowSpaces: Boolean? = true, allowNumbers: Boolean? = false, allowText: Boolean? = true, allowNonAlphaNum: Boolean? = false, textWatcher: TextWatcher? = null) {
        if (this.textWatcher != null) {
            editText!!.removeTextChangedListener(this.textWatcher)
        }
        setParams(initValue, allowSpaces, allowNumbers, allowText, allowNonAlphaNum)
        this.textWatcher = textWatcher
        if (textWatcher != null) {
            editText!!.addTextChangedListener(textWatcher)
        }
    }

    fun setParams(initValue: String? = "", allowSpaces: Boolean? = true, allowNumbers: Boolean? = false, allowText: Boolean? = true, allowNonAlphaNum: Boolean? = false, ) {
        value = initValue
        this.allowSpaces = allowSpaces
        this.allowNumbers = allowNumbers
        this.allowText = allowText
        this.allowNonAlphaNum = allowNonAlphaNum
        //this.okButton = okButton

        //editText.setKeyListener(DigitsKeyListenerWithComma.getInstance(minValue < 0, step != Math.rint(step)));
        if (textWatcher != null) {
            editText!!.removeTextChangedListener(textWatcher)
        }
        editText!!.setText(value)
        if (textWatcher != null) {
            editText!!.addTextChangedListener(textWatcher)
        }
    }

    @JvmName("setValue1") fun setValue(value: String?) {
        if (textWatcher != null) {
            editText!!.removeTextChangedListener(textWatcher)
        }
        editText!!.setText(value)
        if (textWatcher != null) {
            editText!!.addTextChangedListener(textWatcher)
        }
    }

    fun getText(): String {
        return editText?.getText().toString()
    }
}