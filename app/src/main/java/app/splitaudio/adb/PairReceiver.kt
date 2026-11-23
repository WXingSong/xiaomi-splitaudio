package app.splitaudio.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.splitaudio.R

/** 通知栏 RemoteInput 送来的 6 位配对码 */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ACT_CODE) return
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Pairing.KEY_CODE)?.toString()?.trim().orEmpty()
        if (!SetupLogic.validCode(code)) {
            Toast.makeText(c, R.string.pair_bad_code, Toast.LENGTH_SHORT).show()
            Pairing.failReason = "bad_code"; Pairing.set(Pairing.St.FOUND); Pairing.notifyInput(c, alertFresh = false); return
        }
        Pairing.submit(c, code)
    }
    companion object { const val ACT_CODE = "app.splitaudio.adb.PAIR_CODE" }
}
