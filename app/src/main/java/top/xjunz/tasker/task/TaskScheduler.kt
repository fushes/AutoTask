package top.xjunz.tasker.task

import android.os.Looper
import top.xjunz.tasker.engine.runtime.Event
import top.xjunz.tasker.engine.runtime.FlowRuntime
import top.xjunz.tasker.service.AutomatorService
import top.xjunz.tasker.task.event.TaskEventDispatcher

/**
 * @author xjunz 2022/08/05
 */
class TaskScheduler(private val service: AutomatorService, private val looper: Looper) :
    TaskEventDispatcher.Callback {

    private val dispatcher = TaskEventDispatcher(looper, this)

    /**
     * Schedule all active tasks, all of which are running in the thread that owns the [looper].
     */
    fun scheduleTasks() {
        service.uiAutomatorBridge.addOnAccessibilityEventListener {
            dispatcher.processAccessibilityEvent(it)
        }
        service.uiAutomatorBridge.startReceivingEvents()
    }

    /**
     * Destroy the scheduler. After destroyed, you should not use it any more.
     */
    fun destroy() {
        service.uiAutomatorBridge.stopReceivingEvents()
        dispatcher.removePendingEvents()
        FlowRuntime.drainPool()
    }

    override fun onEvent(events: Array<Event>) {
        for (task in TaskManager.getActiveTasks()) {
            if (!task.isActive) continue
            try {
                task.launch(events)
            } finally {
                for (event in events)
                    event.recycle()
            }
        }
    }
}