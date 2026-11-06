package dev.daniel.tvhub.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Runs on the TV as a tiny always-on process, started by the phone over adb:
 *
 *   CLASSPATH=<this apk> app_process /system/bin --nice-name=tvhub-keys dev.daniel.tvhub.tv.KeyServer
 *
 * It reads commands from stdin and injects key events directly, which takes a few milliseconds
 * instead of the ~0.5 s that starting `input keyevent` costs for every press.
 *
 * Commands (one per line):  K <code> [<code>…]   press keys
 *                           T <text>             type text
 *                           L <code>             long-press a key
 */
object KeyServer {
    @JvmStatic
    fun main(args: Array<String>) {
        val inj = try { Injector() } catch (t: Throwable) { println("FAIL ${t.javaClass.simpleName}"); System.out.flush(); return }
        println("READY")
        System.out.flush()
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            try { handle(inj, line) } catch (_: Throwable) {}
        }
    }

    private fun handle(inj: Injector, line: String) {
        when {
            line.startsWith("K ") -> line.substring(2).split(' ').mapNotNull { it.trim().toIntOrNull() }.forEach { inj.press(it) }
            line.startsWith("L ") -> line.substring(2).trim().toIntOrNull()?.let { inj.longPress(it) }
            line.startsWith("T ") -> inj.type(line.substring(2))
        }
    }
}

private class Injector {
    private val manager: Any
    private val inject: Method
    private val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    init {
        // Android 14 moved the injector to InputManagerGlobal; older versions have it on InputManager.
        val cls = try { Class.forName("android.hardware.input.InputManagerGlobal") } catch (_: ClassNotFoundException) {
            Class.forName("android.hardware.input.InputManager")
        }
        manager = cls.getDeclaredMethod("getInstance").invoke(null)!!
        inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private fun send(e: InputEvent) { inject.invoke(manager, e, 0 /* async */) }

    private fun event(action: Int, code: Int, time: Long, repeat: Int = 0, flags: Int = 0) = KeyEvent(
        time, time, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        flags or KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD,
    )

    fun press(code: Int) {
        val now = SystemClock.uptimeMillis()
        send(event(KeyEvent.ACTION_DOWN, code, now))
        send(event(KeyEvent.ACTION_UP, code, now))
    }

    fun longPress(code: Int) {
        val now = SystemClock.uptimeMillis()
        send(event(KeyEvent.ACTION_DOWN, code, now))
        send(event(KeyEvent.ACTION_DOWN, code, now, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS))
        Thread.sleep(600)
        send(event(KeyEvent.ACTION_UP, code, SystemClock.uptimeMillis()))
    }

    fun type(text: String) {
        val events = charMap.getEvents(text.toCharArray()) ?: return
        events.forEach { send(it) }
    }
}
