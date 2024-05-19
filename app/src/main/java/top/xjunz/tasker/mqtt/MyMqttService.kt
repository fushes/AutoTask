package top.xjunz.tasker.mqtt

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings.Secure
import android.util.Log
import android.widget.Toast
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage


class MyMqttService : Service() {

    private var mqttAndroidClient: MqttAndroidClient? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MyMqttService = this@MyMqttService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        val serverUri = "tcp://192.168.1.112:8082"
        val clientId = Build.BRAND + "_" + Build.DEVICE + "_" + getDeviceName(applicationContext);
        var topic = "android-topic/" + clientId;
        mqttAndroidClient =
            MqttAndroidClient(this, serverUri, clientId, MqttAndroidClient.Ack.AUTO_ACK)

        mqttAndroidClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    Log.d("MQTT", "Reconnected to $serverURI")
                    subscribeToTopic(topic)
                } else {
                    Log.d("MQTT", "Connected to $serverURI")
                    subscribeToTopic(topic)
                }
            }

            override fun connectionLost(cause: Throwable) {
                Log.d("MQTT", "Connection lost: $cause")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Toast.makeText(
                    applicationContext,
                    java.lang.String(message?.payload),
                    Toast.LENGTH_SHORT
                ).show()
                Log.d("MQTT", "Received message on $topic: ${java.lang.String(message?.payload)}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        connectToMqtt()
    }


    private fun connectToMqtt() {
        val options = MqttConnectOptions()
        options.isAutomaticReconnect = true
        options.isCleanSession = true
        options.userName = "mqtt"
        options.password = "2e51bf05564158d7eff6d5f0b9fcbb5f".toCharArray();

        try {
            mqttAndroidClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d("MQTT", "onSuccess")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d("MQTT", "onFailure")
                }

            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceName(context: Context): String {
        val deviceName = Secure.getString(context.contentResolver, Secure.ANDROID_ID)
        return deviceName
    }

    private fun subscribeToTopic(topic: String) {
        try {
            mqttAndroidClient?.subscribe(topic, 0)?.also { token ->
                token.actionCallback = object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d("MQTT", "Subscribed to $topic")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e("MQTT", "Failed to subscribe to $topic: $exception")
                    }
                }
            }
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    // 在MqttService类中添加如下方法

    fun publishMessage(topic: String, message: String) {
        try {
            val mqttMessage = MqttMessage(message.toByteArray())
            mqttMessage.qos = 0 // 设置QoS，0表示最多一次，1表示至少一次，2表示正好一次
            mqttAndroidClient?.publish(topic, mqttMessage)?.also { token ->
                token.actionCallback = object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d("MQTT", "Message published to $topic")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e("MQTT", "Failed to publish message to $topic: $exception")
                    }
                }
            }
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttAndroidClient?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}