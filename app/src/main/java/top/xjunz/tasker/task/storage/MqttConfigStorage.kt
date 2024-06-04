/*
 * mqtt
 */

package top.xjunz.tasker.task.storage

import android.annotation.SuppressLint
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
        var out: String = ""
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
            Log.i("MQTT", "path:$configPath, data:${json}")
            FileUtil.writeString(
                json.toString(),
                configPath,
                Charset.defaultCharset()
            )
        }

        fun getConfig(): Config {
            if (!FileUtil.exist(configPath)) {
                init(app)
            }
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
            config.out = "android-topic/${config.clientId}/out"
            return config
        }

        fun init(applicationContext: Context) {
            val config = Config()
            config.serverUri = "tcp://127.0.0.1:8082"
            config.clientId =
                Build.BRAND + "_" + Build.DEVICE + "_" + getDeviceName(applicationContext);

            config.topic = getDefaultTopics(config.clientId)
            config.userName = "mqtt"
            config.password = "2e51bf05564158d7eff6d5f0b9fcbb5f";
            setConfig(config)
        }

        fun getDefaultTopics(clientId: String): HashSet<String> {
            return CollUtil.newHashSet("android-topic/${clientId}", "android-topic/common")
        }

        @SuppressLint("HardwareIds")
        fun getDeviceName(context: Context): String {
            val deviceName =
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            return deviceName
        }
    }


}