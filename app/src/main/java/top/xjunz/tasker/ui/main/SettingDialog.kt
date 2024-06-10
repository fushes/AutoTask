/*
 * Copyright (c) 2023 xjunz. All rights reserved.
 */

package top.xjunz.tasker.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import cn.hutool.core.collection.CollUtil
import cn.hutool.core.util.StrUtil
import top.xjunz.tasker.R
import top.xjunz.tasker.app
import top.xjunz.tasker.databinding.DialogSettingBinding
import top.xjunz.tasker.ktx.*
import top.xjunz.tasker.service.MyMqttService
import top.xjunz.tasker.service.myMqttService
import top.xjunz.tasker.task.storage.MqttConfigStorage
import top.xjunz.tasker.ui.base.BaseDialogFragment
import top.xjunz.tasker.ui.main.EventCenter.doOnEventRouted
import top.xjunz.tasker.ui.main.MainViewModel.Companion.peekMainViewModel
import top.xjunz.tasker.util.ClickListenerUtil.setNoDoubleClickListener


/**
 * @author xjunz 2023/02/28
 */
class SettingDialog : BaseDialogFragment<DialogSettingBinding>() {

    companion object {
        const val HOST_PRIVACY_POLICY = "privacy_policy"
    }

    val config: MqttConfigStorage.Config = MqttConfigStorage.getConfig()

    override val isFullScreen: Boolean = false

    private var observed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.etServerUrl.setText(config.serverUri)
        binding.etTopics.setText(CollUtil.join(config.topic, ","))
        binding.etUserName.setText(config.userName)
        binding.etPassword.setText(config.password)
        val mvm = peekMainViewModel()
        doOnEventRouted(HOST_PRIVACY_POLICY) {
            PrivacyPolicyDialog().show(childFragmentManager)
        }
        binding.btnQuit.setNoDoubleClickListener {
            dismiss()
        }
        binding.btnAdd.setNoDoubleClickListener {
            println(binding.etServerUrl.text)
            if (StrUtil.isEmpty(binding.etServerUrl.text)) {
                toast(R.string.error_empty_input)
                binding.etServerUrl.shake()
                return@setNoDoubleClickListener
            }
            if (StrUtil.isEmpty(binding.etTopics.text)) {
                toast(R.string.error_empty_input)
                binding.etTopics.shake()
                return@setNoDoubleClickListener
            }
            if (StrUtil.isEmpty(binding.etUserName.text)) {
                toast(R.string.error_empty_input)
                binding.etUserName.shake()
                return@setNoDoubleClickListener
            }
            if (StrUtil.isEmpty(binding.etPassword.text)) {
                toast(R.string.error_empty_input)
                binding.etPassword.shake()
                return@setNoDoubleClickListener
            }
            config.serverUri = binding.etServerUrl.text.toString()
            config.topic = CollUtil.newHashSet(binding.etTopics.text.toString().split(","))
            config.userName = binding.etUserName.text.toString()
            config.password = binding.etPassword.text.toString()
            MqttConfigStorage.setConfig(config)
            toast(R.string.succeeded)
        }
        binding.btnTest.setNoDoubleClickListener {
            myMqttService.stopSelf()
            val intent = Intent(app, MyMqttService::class.java)
            myMqttService.startService(intent)
        }
    }
}