/*
 * Copyright (c) 2023 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.main

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.database.DataSetObserver
import android.os.Bundle
import android.view.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.shape.MaterialShapeDrawable
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import top.xjunz.shared.trace.logcatStackTrace
import top.xjunz.shared.utils.illegalArgument
import top.xjunz.tasker.*
import top.xjunz.tasker.databinding.ActivityMainBinding
import top.xjunz.tasker.engine.applet.util.hierarchy
import top.xjunz.tasker.engine.dto.XTaskDTO
import top.xjunz.tasker.engine.task.XTask
import top.xjunz.tasker.ktx.*
import top.xjunz.tasker.premium.PremiumMixin
import top.xjunz.tasker.service.HandleMqttMsg
import top.xjunz.tasker.service.MyMqttService
import top.xjunz.tasker.service.floatingInspector
import top.xjunz.tasker.service.isPremium
import top.xjunz.tasker.service.serviceController
import top.xjunz.tasker.task.applet.option.AppletOptionFactory
import top.xjunz.tasker.task.inspector.InspectorMode
import top.xjunz.tasker.task.runtime.LocalTaskManager
import top.xjunz.tasker.task.runtime.LocalTaskManager.isEnabled
import top.xjunz.tasker.task.runtime.ResidentTaskScheduler
import top.xjunz.tasker.task.storage.TaskStorage
import top.xjunz.tasker.ui.base.DialogStackMixin
import top.xjunz.tasker.ui.outer.GlobalCrashHandler
import top.xjunz.tasker.ui.purchase.PurchaseDialog.Companion.showPurchaseDialog
import top.xjunz.tasker.ui.service.ServiceStarterDialog
import top.xjunz.tasker.ui.task.editor.FlowEditorDialog
import top.xjunz.tasker.ui.task.inspector.FloatingInspectorDialog
import top.xjunz.tasker.ui.task.showcase.*
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener
import top.xjunz.tasker.util.ClickListenerUtil.setOnDoubleClickListener
import top.xjunz.tasker.util.ShizukuUtil
import java.io.File
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream


/**
 * @author xjunz 2021/6/20 21:05
 */
class MainActivity : AppCompatActivity(), DialogStackManager.Callback {

    private val mainViewModel by viewModels<MainViewModel>()

    private val binding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val viewModel by viewModels<TaskShowcaseViewModel>()

    private val scrollTargets = arrayOfNulls<ScrollTarget>(4)

    private lateinit var fabBehaviour: HideBottomViewOnScrollBehavior<View>

    private val bottomItemIds
        get() = intArrayOf(
            R.id.item_running_tasks,
            R.id.item_resident_tasks,
            R.id.item_oneshot_tasks
        )

    private val viewPagerAdapter by lazy {
        object : FragmentStateAdapter(this) {

            override fun getItemCount(): Int = 4

            override fun createFragment(position: Int): Fragment {
                val f = when (position) {
                    0 -> EnabledTaskFragment()
                    1 -> ResidentTaskFragment()
                    2 -> OneshotTaskFragment()
                    3 -> AboutFragment()
                    else -> illegalArgument()
                }
                scrollTargets[position] = f
                return f
            }
        }
    }

    private var currentOperatingFile: File? = null

    private lateinit var fileShareLauncher: ActivityResultLauncher<Intent>

    private lateinit var saveToSAFLauncher: ActivityResultLauncher<Intent>


    //创建观察者
    private val mqttObserver: DataSetObserver = object : DataSetObserver() {
        /**
         * 当Adapter的notifyDataSetChanged方法执行时被调用
         */
        override fun onChanged() {
            viewModel.onNewTaskAdded.value = HandleMqttMsg.taskList.last()
            super.onChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lightTheme = resources.getBoolean(R.bool.lightTheme)
        if ((lightTheme && Preferences.nightMode == AppCompatDelegate.MODE_NIGHT_YES)
            || (!lightTheme && Preferences.nightMode == AppCompatDelegate.MODE_NIGHT_NO)
        ) {
            AppCompatDelegate.setDefaultNightMode(Preferences.nightMode)
            return // recreate
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        fileShareLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                currentOperatingFile?.delete()
                currentOperatingFile = null
            }
        saveToSAFLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                currentOperatingFile?.delete()
            }
        app.setAppTheme(theme)
        setContentView(binding.root)
        initViews()
        observeData()
        initServiceController()
        DialogStackManager.setCallback(this)
        if (!Preferences.privacyPolicyAcknowledged) {
            PrivacyPolicyDialog().show(supportFragmentManager)
        }
        mainViewModel.checkForUpdates()
        if (intent.action == Intent.ACTION_VIEW && intent.scheme == "content") {
            mainViewModel.requestImportTask.value = intent
        }
        val myMqttService = Intent(this@MainActivity, MyMqttService::class.java)
        startService(myMqttService)
        GlobalCrashHandler.init()
        HandleMqttMsg.adapter.registerDataSetObserver(mqttObserver)
    }

    private var isExited = false

    override fun onDialogPush(stack: Stack<DialogStackManager.StackEntry>) {
        if (!DialogStackManager.isVisible(null) && !isExited) {
            DialogStackMixin.animateExit(window)
            isExited = true
        }
    }

    override fun onDialogPop(stack: Stack<DialogStackManager.StackEntry>) {
        if (DialogStackManager.isVisible(null) && isExited) {
            DialogStackMixin.animateReturn(window)
            isExited = false
        }
    }

    private fun initServiceController() {
        serviceController.setStateListener(mainViewModel)
        serviceController.bindExistingServiceIfExists()
    }

    override fun onResume() {
        super.onResume()
        serviceController.syncStatus()
    }

    private fun observeData() {
        observeTransient(viewModel.requestEditTask) {
            val task = it.first
            val prevChecksum = task.checksum
            FlowEditorDialog().initBase(task, false).doOnTaskEdited {
                viewModel.updateTask(prevChecksum, task)
            }.setFlowToNavigate(it.second?.hierarchy).show(supportFragmentManager)
        }
        observeTransient(viewModel.requestTrackTask) {
            FlowEditorDialog().initBase(it, true).setTrackMode().show(supportFragmentManager)
        }
        observeTransient(viewModel.requestToggleTask) { task ->
            when (task.metadata.taskType) {
                XTask.TYPE_RESIDENT -> {
                    val title = if (task.isEnabled) R.string.prompt_disable_task.text
                    else R.string.prompt_enable_task.text
                    makeSimplePromptDialog(msg = title) {
                        if (!task.isEnabled && !isPremium && LocalTaskManager.getEnabledResidentTasks().size ==
                            ResidentTaskScheduler.MAX_ENABLED_RESIDENT_TASKS_FOR_NON_PREMIUM_USER
                        ) {
                            showPurchaseDialog(R.string.tip_purchase_premium_max_resident_tasks)
                        } else {
                            viewModel.toggleTask(task)
                        }
                    }.setNeutralButton(
                        if (task.isEnabled) R.string.disable_all_tasks else R.string.enable_all_tasks
                    ) { _, _ ->
                        val tasks = TaskStorage.getAllTasks().filter {
                            it.isResident && it.isEnabled == task.isEnabled
                        }.toList()
                        if (!task.isEnabled && !isPremium && tasks.size + LocalTaskManager.getEnabledResidentTasks().size >
                            ResidentTaskScheduler.MAX_ENABLED_RESIDENT_TASKS_FOR_NON_PREMIUM_USER
                        ) {
                            showPurchaseDialog(R.string.tip_purchase_premium_max_resident_tasks)
                        } else {
                            tasks.forEach {
                                viewModel.toggleTask(it)
                            }
                        }
                    }.show().also {
                        it.getButton(DialogInterface.BUTTON_NEUTRAL)
                            .setTextColor(ColorScheme.colorError)
                    }
                }

                XTask.TYPE_ONESHOT -> {
                    if (!serviceController.isServiceRunning) {
                        ServiceStarterDialog().show(supportFragmentManager)
                    } else {
                        FloatingInspectorDialog().setMode(InspectorMode.TASK_ASSISTANT)
                            .doOnSucceeded {
                                floatingInspector.viewModel.isCollapsed.value = false
                            }.show(supportFragmentManager)
                    }
                }
            }
        }
        observeDangerousConfirmation(
            viewModel.requestDeleteTask, R.string.prompt_delete_task, R.string.delete
        ) {
            viewModel.deleteRequestedTask()
        }
        observeTransient(viewModel.onNewTaskAdded) {
            when (it.metadata.taskType) {
                XTask.TYPE_ONESHOT -> {
                    binding.viewPager.currentItem = 2
                }

                XTask.TYPE_RESIDENT -> {
                    binding.viewPager.currentItem = 1
                }
            }
        }
        observeTransient(viewModel.requestAddNewTasks) {
            viewModel.addRequestedTasks()
        }
        observeTransient(viewModel.onTaskDeleted) {
            fabBehaviour.slideUp(binding.fabAction)
        }
        observe(mainViewModel.isServiceRunning) {
            binding.btnServiceControl.isActivated = it
            if (it) {
                binding.btnServiceControl.setText(R.string.stop_service)
                binding.btnServiceControl.setIconResource(R.drawable.ic_baseline_stop_24)
                viewModel.listenTaskPauseStateChanges()
            } else {
                binding.btnServiceControl.setText(R.string.start_service)
                binding.btnServiceControl.setIconResource(R.drawable.ic_baseline_play_arrow_24)
            }
        }
        observeDialog(mainViewModel.serviceBindingError) {
            if (it is TimeoutException) {
                makeSimplePromptDialog(msg = R.string.prompt_shizuku_time_out).setTitle(R.string.error_occurred)
                    .setNegativeButton(R.string.launch_shizuku_manager) { _, _ ->
                        ShizukuUtil.launchShizukuManager()
                    }.setPositiveButton(R.string.retry) { _, _ ->
                        serviceController.bindService()
                    }.show()
            } else {
                val error = if (it is Throwable) it.stackTraceToString() else it.toString()
                if (error.contains("registered")) {
                    makeSimplePromptDialog(
                        title = R.string.tip,
                        msg = R.string.error_automation_already_registered
                    ).show()
                } else {
                    showErrorDialog(error)
                }
            }
        }
        observeDangerousConfirmation(
            mainViewModel.stopServiceConfirmation, R.string.prompt_stop_service, R.string.stop
        ) {
            mainViewModel.toggleService()
        }
        observe(PremiumMixin.premiumStatusLiveData) {
            if (it && !upForGrabs) {
                binding.tvTitle.setDrawableEnd(R.drawable.ic_verified_24px)
            } else {
                binding.tvTitle.setDrawableEnd(null)
            }
        }
//        observe(app.updateInfo) {
//            if (it.hasUpdates() && mainViewModel.showUpdateDialog) {
//                MaterialAlertDialogBuilder(this).setTitle(R.string.has_updates)
//                    .setMessage(it.formatToString())
//                    .setOnDismissListener {
//                        mainViewModel.showUpdateDialog = false
//                    }
//                    .setOnCancelListener {
//                        mainViewModel.showUpdateDialog = false
//                    }
//                    .setPositiveButton(R.string.download) { _, _ ->
//                        viewUrlSafely("https://spark.appc02.com/tasker")
//                    }.setNegativeButton(android.R.string.cancel, null).show()
//            }
//        }
        //分享任务
        observeTransient(mainViewModel.requestShareFile) {
            currentOperatingFile = it
            fileShareLauncher.launch(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .addCategory(Intent.CATEGORY_DEFAULT)
                        .putExtra(Intent.EXTRA_STREAM, it.makeContentUri())
                        .setType("*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    R.string.share_this_task.str
                )
            )
        }
        //分享任务
        observeTransient(mainViewModel.requestUploadFile) {
            HandleMqttMsg.sendMsg(it,HandleMqttMsg.MsgType.UPLOAD_TASK)
        }
        observeTransient(mainViewModel.requestImportTask) {
            handleImportTask(it)
        }
        mainViewModel.taskNumbers.forEachIndexed { index, ld ->
            observeNotNull(ld) {
                binding.bottomBar.getOrCreateBadge(bottomItemIds[index]).number = it
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        binding.tvTitle.setOnDoubleClickListener {
            Preferences.showDragToMoveTip = true
            Preferences.showToggleRelationTip = true
            Preferences.showSwipeToRemoveTip = true
            Preferences.showLongClickToSelectTip = true
            PremiumMixin.clearPremium()
        }
        if (BuildConfig.DEBUG) {
            binding.tvTitle.setOnLongClickListener {
                error("Crash test!")
            }
        }
        binding.appBar.applySystemInsets { v, insets ->
            v.updatePadding(top = insets.top)
            v.doOnPreDraw {
                mainViewModel.appbarHeight.value = it.height
            }
        }
        binding.bottomBar.applySystemInsets { v, insets ->
            v.updatePadding(bottom = insets.bottom)
        }
        binding.fabAction.doOnPreDraw {
            mainViewModel.paddingBottom.value = it.height + binding.bottomBar.height
        }
        binding.bottomBar.background.let {
            it as MaterialShapeDrawable
            it.elevation = 4.dpFloat
            it.alpha = (.88 * 0xFF).toInt()
        }
        binding.btnServiceControl.setNoDoubleClickListener {
            if (it.isActivated) {
                mainViewModel.stopServiceConfirmation.value = true
            } else {
                ServiceStarterDialog().show(supportFragmentManager)
            }
        }
        binding.fabAction.setNoDoubleClickListener {
            TaskCreatorDialog().show(supportFragmentManager)
        }
        binding.viewPager.adapter = viewPagerAdapter
        binding.bottomBar.setOnItemSelectedListener {
            binding.viewPager.currentItem = binding.bottomBar.menu.indexOf(it)
            return@setOnItemSelectedListener true
        }
        fabBehaviour =
            ((binding.fabAction.layoutParams as CoordinatorLayout.LayoutParams).behavior as HideBottomViewOnScrollBehavior<View>)
        binding.viewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.bottomBar.selectedItemId =
                    binding.bottomBar.menu.getItem(position).itemId
                ((binding.bottomBar.layoutParams as CoordinatorLayout.LayoutParams).behavior
                        as HideBottomViewOnScrollBehavior<View>).slideUp(
                    binding.bottomBar,
                    true
                )
                fabBehaviour.slideUp(binding.fabAction, true)
                binding.appBar.setLiftOnScrollTargetView(scrollTargets[position]?.getScrollTarget())
                for (i in 0..bottomItemIds.lastIndex) {
                    binding.bottomBar.getOrCreateBadge(bottomItemIds[i]).isVisible = i == position
                }
            }
        })
    }

    @SuppressLint("MissingSuperCall")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // On old devices, onStart() may be called after onNewIntent(), hence there
        // will be no active observers.
        lifecycleScope.launch {
            lifecycle.withStarted {
                if (intent.action == Intent.ACTION_VIEW && intent.scheme == "content") {
                    mainViewModel.requestImportTask.value = intent
                } else {
                    mainViewModel.onNewIntent.setValueIfObserved(intent.data to EventCenter.fetchTransientValue())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceController.unbindService()
        DialogStackManager.destroyAll()
        ColorScheme.release()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun handleImportTask(result: Intent?) {
        if (result == null) return
        // Collect Uris
        val uris = arrayListOf(result.data)
        val clipData = result.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                uris.add(clipData.getItemAt(i)?.uri)
            }
        }
        val tasks = arrayListOf<XTask>()
        // Collect data
        for (uri in uris) {
            if (uri == null) continue
            try {
                runCatching {
                    contentResolver.openInputStream(uri)?.use {
                        val dto = Json.decodeFromStream<XTaskDTO>(it)
                        tasks.add(dto.toXTask(AppletOptionFactory, true))
                    }
                }.onFailure {
                    ZipInputStream(contentResolver.openInputStream(uri)).use {
                        var entry: ZipEntry? = it.nextEntry
                        while (entry != null) {
                            val dto = Json.decodeFromStream<XTaskDTO>(it)
                            tasks.add(dto.toXTask(AppletOptionFactory, true))
                            entry = it.nextEntry
                        }
                    }
                }
            } catch (t: Throwable) {
                t.logcatStackTrace()
            }
        }
        if (tasks.isEmpty()) {
            toast(R.string.error_unsupported_file)
        } else {
            TaskListDialog().setTaskList(tasks).setTitle(R.string.import_tasks.text)
                .show(supportFragmentManager)
        }
    }
}