package info.nightscout.androidaps.utils

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import info.nightscout.androidaps.R
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.utils.ui.WordEditor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*

class WordListEdit(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val view: View?,
    private val resLayoutId: Int,
    private val tagPrefix: String,
    private var label: String,
    private val save: Runnable?) {

    private val maxReplacements = 255
    private val replacementsData = JSONArray()
    private val rows = arrayOfNulls<View>(maxReplacements)
    private val textViewsWords = arrayOfNulls<WordEditor>(maxReplacements)
    private val textViewsReplacements = arrayOfNulls<WordEditor>(maxReplacements)
    private val removeButtons = arrayOfNulls<ImageView>(maxReplacements)
    private var finalAdd: ImageView? = null
    private var layout: LinearLayout? = null
    private var textlabel: TextView? = null
    private var inflatedUntil = -1

    private fun buildView() {
        layout = view?.findViewById(resLayoutId)
        if (layout != null) {
            layout!!.removeAllViewsInLayout()
            textlabel = TextView(context)
            textlabel!!.text = label
            textlabel!!.gravity = Gravity.CENTER
            val llp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            llp.setMargins(0, 5, 0, 5)
            textlabel!!.layoutParams = llp
            //textlabel.setBackgroundColor(ContextCompat.getColor(MainApp.instance(), R.color.linearBlockBackground));
            TextViewCompat.setTextAppearance(textlabel!!, android.R.style.TextAppearance_Medium)
            layout!!.addView(textlabel)
            var i = 0
            while (i < maxReplacements && i < itemsCount()) {
                inflateRow(i)
                inflatedUntil = i
                i++
            }

            // last "plus" to append new interval
            val factor = layout!!.getContext().resources.displayMetrics.density
            finalAdd = ImageView(context)
            finalAdd!!.setImageResource(R.drawable.ic_add)
            val illp = LinearLayout.LayoutParams((35.0 * factor).toInt(), (35 * factor).toInt())
            illp.setMargins(0, 25, 0, 25) // llp.setMargins(left, top, right, bottom);
            illp.gravity = Gravity.CENTER
            layout!!.addView(finalAdd)
            finalAdd!!.layoutParams = illp
            finalAdd!!.setOnClickListener { view: View? ->
                addItem(itemsCount(), true)
                addItem(itemsCount(), false)
                callSave()
                log()
                fillView()
            }
            fillView()
        }
    }

    private fun inflateRow(position: Int) {
        val inflater = LayoutInflater.from(context)
        val resource = R.layout.wordlistedit_element
        rows[position] = inflater.inflate(resource, layout, false)
        val childView = rows[position]
        textViewsWords[position] = childView?.findViewById(R.id.word)
        textViewsReplacements[position] = childView?.findViewById(R.id.replacement)
        removeButtons[position] = childView?.findViewById(R.id.wordlistedit_remove)


        removeButtons[position]!!.setOnClickListener { view: View? ->
            removeItem(position)
            callSave()
            log()
            fillView()
        }

        textViewsWords[position]!!.setTextWatcher(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                editItem(position, true, textViewsWords[position]!!.getText())
                callSave()
                log()
            }

            override fun beforeTextChanged(s: CharSequence, start: Int,
                                           count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int,
                                       before: Int, count: Int) {
            }
        })
        textViewsWords[position]!!.setTag("$tagPrefix-1-$position")

        textViewsReplacements[position]!!.setTextWatcher(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                editItem(position, false, textViewsWords[position]!!.getText())
                callSave()
                log()
            }

            override fun beforeTextChanged(s: CharSequence, start: Int,
                                           count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int,
                                       before: Int, count: Int) {
            }
        })
        textViewsReplacements[position]!!.setTag("$tagPrefix-2-$position")
        layout!!.addView(childView)
    }

    private fun fillView() {
        for (i in 0 until maxReplacements - 1) {
            if (i < itemsCount()) {
                rows[i]!!.visibility = View.VISIBLE
                buildNewRow(i)
            } else if (i <= inflatedUntil) {
                rows[i]!!.visibility = View.GONE
            }
        }
        if (!(itemsCount() > 0 && itemsCount() == maxReplacements)) {
            finalAdd!!.visibility = View.VISIBLE
        } else {
            finalAdd!!.visibility = View.GONE
        }
    }

    private fun buildNewRow(i: Int) {

        val editText1 = textViewsWords[i]
        val editText2 = textViewsReplacements[i]
        editText1!!.setParams()
        editText2!!.setParams()
        if (itemsCount() == 1 || i == 0) {
            removeButtons[i]!!.visibility = View.INVISIBLE
        } else removeButtons[i]!!.visibility = View.VISIBLE
        if (itemsCount() >= maxReplacements) {
            finalAdd!!.visibility = View.INVISIBLE
        } else {
            finalAdd!!.visibility = View.VISIBLE
        }
    }

    private fun itemsCount(): Int {
        return replacementsData.length()
    }

    private fun keyword(index: Int): String {
        try {
            val item = replacementsData[index] as JSONObject
            if (item.has("keyword")) {
                return item.getString("keyword")
            }
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
        return ""
    }

    private fun replacement(index: Int): String {
        try {
            val item = replacementsData[index] as JSONObject
            if (item.has("keyword")) {
                return item.getString("replacement")
            }
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
        return ""
    }

    private fun editItem(index: Int, isWord: Boolean, text: String) {
        try {
            val newObject = JSONObject()
            if (isWord) newObject.put("word", text)
            else newObject.put("replacement", text)
            replacementsData.put(index, newObject)
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
    }

    private fun addItem(index: Int, isWord: Boolean, text: String = "") {
        if (itemsCount() >= maxReplacements) return
        if (itemsCount() > inflatedUntil) {
            layout!!.removeView(finalAdd)
            inflateRow(++inflatedUntil)
            layout!!.addView(finalAdd)
        }
        try {    // add new object
            editItem(index, isWord, text)
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
    }

    private fun removeItem(index: Int) {
        replacementsData.remove(index)
    }

    private fun log() {
        for (i in 0 until replacementsData.length()) {
            aapsLogger.debug(i.toString() + " " + keyword(i) + " " + replacement(i))
        }
    }

    private fun callSave() {
        save?.run()
    }

    fun updateLabel(txt: String) {
        label = txt
        if (textlabel != null) textlabel!!.text = txt
    }

    init {
        buildView()
    }
}