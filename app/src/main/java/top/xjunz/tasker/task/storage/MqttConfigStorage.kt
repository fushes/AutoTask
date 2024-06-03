/*
 * mqtt
 */

package top.xjunz.tasker.task.storage

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import cn.hutool.core.collection.CollUtil
import cn.hutool.core.io.FileUtil
import cn.hutool.json.JSONObject
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
            val json = JSONObject();
            json["serverUri"] = conf.serverUri
            json["userName"] = conf.userName
            json["password"] = conf.password
            json["clientId"] = conf.clientId
            json["topic"] = CollUtil.join(conf.topic, ",")
            Log.i("MQTT","path:$configPath, data:${json.toString()}")
            FileUtil.writeString(
                json.toString(),
                configPath,
                Charset.defaultCharset()
            )
        }

        fun getConfig(): Config {
            init(app)
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
            config.serverUri = "tcp://43.138.157.155:8082"
            config.clientId =
                Build.BRAND + "_" + Build.DEVICE + "_" + getDeviceName(applicationContext);
            config.topic =
                CollUtil.newHashSet("android-topic/${config.clientId}", "android-topic/common")
            config.userName = "mqtt"
            config.password = "2e51bf05564158d7eff6d5f0b9fcbb5f";
            setConfig(config)
        }

        fun getDeviceName(context: Context): String {
            val deviceName =
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            return deviceName
        }
    }


}