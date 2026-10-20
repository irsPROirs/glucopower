package com.example.glucoble

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.android.material.button.MaterialButton

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun mainScreenHasOnlyRealProfilesAndStartStopActions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val all = views(activity.window.decorView)
            assertFalse(all.any { it is android.widget.ScrollView })
            val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            content.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 360, 640)
            for (v in views(content).filterIsInstance<MaterialButton>()) {
                val rect = android.graphics.Rect(0, 0, v.width, v.height)
                (content as ViewGroup).offsetDescendantRectToMyCoords(v, rect)
                assertTrue("Button outside screen: ${v.text}: $rect", rect.bottom <= content.height && rect.top >= 0)
                assertTrue("Button has no height", v.height > 0)
            }
            val buttons = all.filterIsInstance<MaterialButton>().map { it.text.toString() }
            assertEquals(setOf("Каденс", "ЧСС", "Мощность", "Начать вещание", "Остановить"), buttons.toSet())
            assertFalse(all.filterIsInstance<TextView>().any { it.text.toString().contains("Тест каденса") })
            all.filterIsInstance<MaterialButton>().first { it.text == "ЧСС" }.performClick()
            assertEquals("HEART_GLUCOSE", activity.getSharedPreferences("bridge", 0).getString("ui_profile", null))
        } finally { controller.pause().stop().destroy() }
    }
}
