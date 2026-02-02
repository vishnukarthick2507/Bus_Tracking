package com.example.driver

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 🔁 Auto refresh BUS ID for Driver / Student
 * ✔ No Firebase Service here
 * ✔ No notification code here
 * ✔ SAFE + CLEAN
 */

private const val BASE_URL = "http://10.91.235.165:5000"

private fun prefs(ctx: Context): SharedPreferences =
    ctx.getSharedPreferences("smartbus", Context.MODE_PRIVATE)

@Composable
fun BusAutoRefresh(
    role: String?,
    currentBusId: String?,
    onBusChanged: (String) -> Unit
) {
    val ctx = LocalContext.current
    val p = prefs(ctx)

    LaunchedEffect(role, currentBusId) {
        if (role == null) return@LaunchedEffect

        delay(1000) // ⏱️ small delay to avoid spam

        when (role) {

            "DRIVER" -> {
                val phone = p.getString("driverPhone", null) ?: return@LaunchedEffect

                fetchDriverBus(ctx, phone) { newBus ->
                    if (newBus != currentBusId) {
                        p.edit().putString("busId", newBus).apply()
                        onBusChanged(newBus)
                    }
                }
            }

            "STUDENT" -> {
                val reg = p.getString("studentReg", null)
                val phone = p.getString("studentPhone", null)

                if (reg != null && phone != null) {
                    fetchStudentBus(ctx, reg, phone) { newBus ->
                        if (newBus != currentBusId) {
                            p.edit().putString("busId", newBus).apply()
                            onBusChanged(newBus)
                        }
                    }
                }
            }
        }
    }
}

/* ================= API CALLS ================= */

private fun fetchDriverBus(
    ctx: Context,
    phone: String,
    onSuccess: (String) -> Unit
) {
    val body = JSONObject().put("phone", phone)

    val req = JsonObjectRequest(
        Request.Method.POST,
        "$BASE_URL/driver/check-driver",
        body,
        { res ->
            val busId = res.getJSONObject("driver").getString("busId")
            onSuccess(busId)
        },
        {
            // silent fail (no crash)
        }
    )

    Volley.newRequestQueue(ctx).add(req)
}

private fun fetchStudentBus(
    ctx: Context,
    reg: String,
    phone: String,
    onSuccess: (String) -> Unit
) {
    val body = JSONObject()
        .put("regNo", reg)
        .put("phone", phone)

    val req = JsonObjectRequest(
        Request.Method.POST,
        "$BASE_URL/student/check-student",
        body,
        { res ->
            val busId = res.getJSONObject("student").getString("busId")
            onSuccess(busId)
        },
        {
            // silent fail
        }
    )

    Volley.newRequestQueue(ctx).add(req)
}
