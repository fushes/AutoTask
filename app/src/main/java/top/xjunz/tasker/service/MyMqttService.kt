package top.xjunz.tasker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import cn.hutool.core.collection.CollUtil
import cn.hutool.core.util.StrUtil
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import top.xjunz.tasker.R
import top.xjunz.tasker.annotation.Privileged
import top.xjunz.tasker.ktx.toast
import top.xjunz.tasker.service.controller.A11yAutomatorServiceController
import top.xjunz.tasker.service.controller.ShizukuAutomatorServiceController
import top.xjunz.tasker.task.runtime.IOnDataSendListener
import top.xjunz.tasker.task.storage.MqttConfigStorage
import java.lang.ref.WeakReference


class MyMqttService : Service() {
    private val SERVICE_ID = 1
    private var config: MqttConfigStorage.Config = MqttConfigStorage.getConfig()

    companion object {

        private var instance: WeakReference<MyMqttService>? = null

        @Privileged
        fun require(): MyMqttService {
            return requireNotNull(instance?.get()) {
                "The MyMqttService is not yet started or has dead!"
            }
        }

        @Privileged
        fun get(): MyMqttService? {
            return instance?.get()
        }
    }

    private var mqttAndroidClient: MqttAndroidClient? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MyMqttService = this@MyMqttService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        config = MqttConfigStorage.getConfig()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel("channel", "keep", NotificationManager.IMPORTANCE_NONE)
        notificationManager.createNotificationChannel(channel)
        val notification = Notification.Builder(this, "channel").build()
        startForeground(SERVICE_ID, notification)
        if (StrUtil.isEmpty(config.serverUri) || StrUtil.isEmpty(config.clientId) || CollUtil.isEmpty(
                config.topic
            )
        ) {
            toast(R.string.mqtt_connect_fail)
            Log.e("MQTT", "MQTT not config")
            return
        }
        mqttAndroidClient =
            MqttAndroidClient(
                this,
                config.serverUri,
                config.clientId,
                MqttAndroidClient.Ack.AUTO_ACK
            )

        mqttAndroidClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    Log.d("MQTT", "Reconnected to $serverURI")
                    config.topic?.forEach { item -> subscribeToTopic(item) }
                } else {
                    Log.d("MQTT", "Connected to $serverURI")
                    config.topic?.forEach { item -> subscribeToTopic(item) }

                }
                if (OperatingMode.CURRENT.VALUE == OperatingMode.Privilege.VALUE) {
                    ShizukuAutomatorServiceController.remoteService?.taskManager?.setOnDataSendListener(
                        iOnDataSendListener
                    )
                } else {
                    A11yAutomatorServiceController.service?.getTaskManager()?.setOnDataSendListener(iOnDataSendListener)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                return
            }
//
//            override fun connectionLost(cause: Throwable) {
//                try {
//                    mqttAndroidClient?.unregisterResources();
//                    mqttAndroidClient?.disconnect(null, object : IMqttActionListener {
//                        override fun onSuccess(asyncActionToken: IMqttToken?) {
//                            Log.d("MQTT", "断开连接")
//                        }
//
//                        override fun onFailure(
//                            asyncActionToken: IMqttToken?,
//                            exception: Throwable?
//                        ) {
//                            Log.d("MQTT", "断开失败")
//                        }
//                    })
//                } catch (e: MqttException) {
//                    Log.d("MQTT", "断开连接--错误")
//                }
//                Log.d("MQTT", "Connection lost: $cause")
//            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val msg = java.lang.String(message?.payload)
                HandleMqttMsg.handMsg(msg.toString())
                Log.d("MQTT", "Received message on $topic: ${java.lang.String(message?.payload)}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        try {
            connectToMqtt()
            instance = WeakReference(this)
        } catch (e: Exception) {
            toast(R.string.mqtt_connect_fail)
            Log.e("MQTT", "client mqtt err")

        }
    }


    private val iOnDataSendListener: IOnDataSendListener = object : IOnDataSendListener.Stub() {
        override fun onSendData(data: String) {
            HandleMqttMsg.sendMsg(data, HandleMqttMsg.MsgType.UPLOAD_DATA)
        }
    }


    fun connectToMqtt() {
        val options = MqttConnectOptions()
        options.isAutomaticReconnect = true
        options.isCleanSession = true
        options.userName = config.userName
        options.password = config.password.toCharArray();
        try {
            mqttAndroidClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d("MQTT", "onSuccess")
                    toast(R.string.mqtt_connect_success)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d("MQTT", "onFailure")
                    toast(R.string.mqtt_connect_fail)
                }

            })
        } catch (e: MqttException) {
            toast(R.string.mqtt_connect_fail)
        }
    }

    fun subscribeToTopic(topic: String) {
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
            Log.d("MQTT", "subscribeToTopic error ${e.message}")
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
                        toast(R.string.succeeded)
                        Log.d("MQTT", "Message published to $topic")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        toast(R.string.failed)
                        Log.e("MQTT", "Failed to publish message to $topic: $exception")
                    }
                }
            }
        } catch (e: MqttException) {
            Log.d("MQTT", "publishMessage error ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}