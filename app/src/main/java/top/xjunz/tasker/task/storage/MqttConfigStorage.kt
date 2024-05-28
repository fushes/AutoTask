/*
 * mqtt
 */

package top.xjunz.tasker.task.storage

import android.content.Context
import android.os.Build
import android.provider.Settings
import cn.hutool.core.collection.CollUtil
import cn.hutool.core.io.FileUtil
import cn.hutool.json.JSONObject
import com.google.gson.Gson
import top.xjunz.tasker.app
import java.io.File
import java.nio.charset.Charset

/**
 */
class MqttConfigStorage {

    class Config {
        var serverUri: String = ""
        var clientId: String = ""
        var topic: HashSet<String>? = null
        var userName: String = ""
        var password: String = ""
    }

    companion object {
        private val storageDir: File = app.getExternalFilesDir("config")!!

        private const val configName: String = "mqtt.conf";
        private val configPath: String = storageDir.path + File.separator + configName
        fun setConfig(conf: Config) {
            val gson = Gson()
            val str: String = gson.toJson(conf) // obj 代表各种数据类型
            val json = JSONObject(str);
            json["topic"] = CollUtil.join(conf.topic, ",")
            FileUtil.writeString(
                json.toString(),
                configPath,
                Charset.defaultCharset()
            )
        }

        fun getConfig(): Config {
            val str: String = FileUtil.readString(
                configPath,
                Charset.defaultCharset()
            )
            val jsonObject = JSONObject(str)
            val config = Config();
            config.serverUri = jsonObject["serverUri"].toString()
            config.clientId = jsonObject["clientId"].toString()
            config.topic = CollUtil.newHashSet(jsonObject["topic"].toString().split(","))
            config.userName = jsonObject["userName"].toString()
            config.password = jsonObject["password"].toString()
            return config
        }

        fun init(applicationContext: Context) {
            val config = Config()
            config.serverUri = "tcp://192.168.1.112:8082"
            config.clientId =
                Build.BRAND + "_" + Build.DEVICE + "_" + getDeviceName(applicationContext);
            config.topic =
                CollUtil.newHashSet("android-topic/${config.clientId}", "android-topic/common")
            config.userName = "mqtt"
            config.password = "2e51bf05564158d7eff6d5f0b9fcbb5f";
            setConfig(config)
        }

        fun getDeviceName(context: Context): String {
            val deviceName = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            return deviceName
        }
    }


}