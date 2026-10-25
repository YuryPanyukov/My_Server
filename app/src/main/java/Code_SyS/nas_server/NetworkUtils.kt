package Code_SyS.nas_server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager

object NetworkUtils {

    /**
     * Преобразует IPv4-адрес из формата WifiManager (little-endian int)
     * в строку "a.b.c.d". Возвращает null, если адрес не задан (0).
     */
    fun formatIp(ipInt: Int): String? {
        if (ipInt == 0) return null
        return "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
    }

    fun getWifiIpAddress(context: Context): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val connectionInfo = wifiManager.connectionInfo
        return formatIp(connectionInfo.ipAddress)
    }

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
            ?: return false
        val network: NetworkInfo? = connectivityManager.activeNetworkInfo
        return network != null && network.type == ConnectivityManager.TYPE_WIFI && network.isConnected
    }
}
