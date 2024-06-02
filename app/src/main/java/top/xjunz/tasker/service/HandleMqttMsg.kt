package top.xjunz.tasker.service

import android.util.Log
import cn.hutool.core.io.IoUtil
import cn.hutool.core.util.HexUtil
import cn.hutool.json.JSONObject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import top.xjunz.tasker.engine.dto.XTaskDTO
import top.xjunz.tasker.engine.dto.toDTO
import top.xjunz.tasker.service.controller.ShizukuAutomatorServiceController
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback
import top.xjunz.tasker.task.runtime.LocalTaskManager.tasks
import top.xjunz.tasker.task.runtime.PrivilegedTaskManager.tasks
import top.xjunz.tasker.task.storage.TaskStorage
import kotlin.properties.Delegates

class HandleMqttMsg {

    enum class MsgType(t: String) {

        DO_TASK("1"),
        ADD_TASK("2"),
        UPLOAD_RESULT("3"),
        UPLOAD_DATA("4");

        var type: String = t

    }

    companion object {

        val TAG = "HandleMqttMsg"


        fun sendMsg(msg: String, type: MsgType) {
            var jsonObject: JSONObject? = null
            try {
                jsonObject = JSONObject(msg)
            } catch (e: Exception) {
                Log.e(TAG, "data error")
            }
            if (null == jsonObject) {
                return
            }
            val taskSnr = jsonObject.getStr("taskSnr")
            val data = jsonObject.getStr("data")

            val result = JSONObject()
            result.set("snr", taskSnr)
            result.set("type", type.type)
            result.set("data", data)
            myMqttService.publishMessage("android-topic/out", result.toString())
        }

        fun handMsg(data: String) {
            var jsonObject: JSONObject? = null
            try {
                jsonObject = JSONObject(data)
            } catch (e: Exception) {
                Log.e(TAG, "msg not handle")
            }
            if (null == jsonObject) {
                return
            }
            val type = jsonObject.getStr("type")
            val data = jsonObject.getStr("data")
            val taskSnr = jsonObject.getStr("taskSnr")
            if (type == MsgType.DO_TASK.type) {
                doTask(data, taskSnr)
                return
            }
            if (type == MsgType.ADD_TASK.type) {
                addTask(data)
                return
            }
        }

        private fun doTask(identifier: String, taskSnr: String) {
            val allTasks = TaskStorage.getAllTasks()
            val get = allTasks.stream().filter { item -> identifier == item.metadata.identifier }
                .findFirst().get()
            get.metadata.taskSnr = taskSnr
            taskCompletionCallback.setSnr(taskSnr)
            ShizukuAutomatorServiceController.remoteService?.taskManager?.addOneshotTaskIfAbsent(
                get.toDTO()
            )
            ShizukuAutomatorServiceController.remoteService?.scheduleOneshotTask(
                get.checksum,
                taskCompletionCallback
            )
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun addTask(data: String) {
            val decodeHex = HexUtil.decodeHex(data)
            val dto = Json.decodeFromStream<XTaskDTO>(IoUtil.toStream(decodeHex))
//            tasks.add(dto.toXTask(AppletOptionFactory, true))
        }

        private val taskCompletionCallback by lazy {
            var snr = ""

            object : ITaskCompletionCallback.Stub() {
                override fun onTaskCompleted(isSuccessful: Boolean) {
                    myMqttService.publishMessage(
                        "android-topic/out",
                        ResultData(snr, isSuccessful).toString()
                    )
                }

                fun setSnr(data: String) {
                    snr = data
                }
            }
        }
    }


    class ResultData(var snr: String, var isSuccessful: Boolean) {
        override fun toString(): String {
            return "{'snr':'$snr', 'data':$isSuccessful,'type':${MsgType.UPLOAD_RESULT.type}}"
        }
    }

}