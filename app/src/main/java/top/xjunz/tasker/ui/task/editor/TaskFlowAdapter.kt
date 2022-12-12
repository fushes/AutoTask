package top.xjunz.tasker.ui.task.editor

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.xjunz.tasker.R
import top.xjunz.tasker.databinding.ItemFlowItemBinding
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.engine.applet.base.Flow
import top.xjunz.tasker.ktx.configHeaderTitle
import top.xjunz.tasker.ktx.indexOf
import top.xjunz.tasker.ktx.modifyAlpha
import top.xjunz.tasker.ktx.show
import top.xjunz.tasker.task.applet.flatSize
import top.xjunz.tasker.task.applet.isContainer
import top.xjunz.tasker.task.applet.isDescendantOf
import top.xjunz.tasker.task.applet.option.AppletOption
import top.xjunz.tasker.task.applet.option.ValueDescriptor
import top.xjunz.tasker.ui.ColorScheme
import top.xjunz.tasker.util.AntiMonkeyUtil.setAntiMoneyClickListener

/**
 * @author xjunz 2022/08/14
 */
class TaskFlowAdapter(private val fragment: FlowEditorDialog) :
    ListAdapter<Applet, TaskFlowAdapter.FlowViewHolder>(FlowItemTouchHelperCallback.DiffCallback) {

    private val viewModel: FlowEditorViewModel by fragment.viewModels()

    private val globalViewModel: GlobalFlowEditorViewModel by fragment.activityViewModels()

    private val itemViewBinder = FlowItemViewBinder(viewModel, globalViewModel)

    private val layoutInflater = LayoutInflater.from(fragment.requireContext())

    private val menuHelper =
        FlowItemMenuHelper(viewModel, globalViewModel.factory, fragment.childFragmentManager)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (viewModel.isReadyOnly) return
        ItemTouchHelper(object : FlowItemTouchHelperCallback(recyclerView, viewModel) {

            override fun onMoveEnded(hasDragged: Boolean, position: Int) {
                if (position == RecyclerView.NO_POSITION) return
                if (hasDragged) {
                    // If dragged and it's the single selection, unselect it
                    if (viewModel.selections.size != 1) return
                    val selection = currentList[position]
                    if (viewModel.selections.first() === selection)
                        viewModel.toggleMultiSelection(selection)
                } else {
                    val applet = currentList[position]
                    if (!viewModel.isMultiSelected(applet))
                        viewModel.toggleMultiSelection(applet)
                }
            }

            override fun shouldBeInvolvedInSwipe(next: Applet, origin: Applet): Boolean {
                val isSelected = viewModel.isMultiSelected(origin)
                if (isSelected) {
                    return viewModel.isMultiSelected(next) || viewModel.selections.any {
                        it is Flow && next.isDescendantOf(it)
                    }
                }
                return super.shouldBeInvolvedInSwipe(next, origin)
            }

            override fun doRemove(parent: Flow, from: Applet): Int {
                if (viewModel.isMultiSelected(from)) {
                    val size = viewModel.selections.flatSize
                    viewModel.selections.forEach {
                        parent.remove(it)
                    }
                    viewModel.selections.clear()
                    return size
                }
                return super.doRemove(parent, from)
            }
        }).attachToRecyclerView(recyclerView)
        // Always clear single selection on attached
        viewModel.singleSelect(-1)
    }

    private inline fun showMultiReferencesSelectorMenu(
        anchor: View,
        option: AppletOption,
        candidates: List<ValueDescriptor>,
        crossinline onSelected: (Int) -> Unit
    ) {
        if (candidates.size == 1) {
            onSelected(option.results.indexOf(candidates.single()))
        } else {
            val popup = PopupMenu(fragment.requireContext(), anchor, Gravity.END)
            popup.menu.add(R.string.which_to_refer)
            candidates.forEach {
                popup.menu.add(it.name)
            }
            popup.setOnMenuItemClickListener {
                onSelected(popup.indexOf(it) - 1)
                return@setOnMenuItemClickListener true
            }
            popup.configHeaderTitle()
            popup.show()
        }
    }

    inner class FlowViewHolder(val binding: ItemFlowItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.tvComment.setBackgroundColor(ColorScheme.colorTertiaryContainer.modifyAlpha(.3))
            binding.root.setAntiMoneyClickListener { view ->
                val applet = currentList[adapterPosition]
                if (viewModel.isSelectingRef && !applet.isContainer) {
                    if (globalViewModel.isRefSelected(applet)) {
                        globalViewModel.removeRefSelection(applet)
                        if (globalViewModel.selectedRefs.isEmpty())
                            viewModel.isFabVisible.value = false
                    } else {
                        val option = globalViewModel.factory.requireOption(applet)
                        val candidates = option.results.filter {
                            viewModel.refValueDescriptor.type == it.type
                        }
                        showMultiReferencesSelectorMenu(view, option, candidates) {
                            val refid = applet.refids[it]
                            if (refid == null) {
                                globalViewModel.addRefSelection(applet, it)
                            } else {
                                globalViewModel.addRefSelectionWithRefid(
                                    viewModel.refSelectingApplet, refid
                                )
                            }
                            if (globalViewModel.selectedRefs.isNotEmpty())
                                viewModel.isFabVisible.value = true
                        }
                    }
                } else if (viewModel.isInMultiSelectionMode) {
                    viewModel.toggleMultiSelection(applet)
                } else {
                    viewModel.singleSelect(adapterPosition)
                    menuHelper.showMenu(view, applet).setOnDismissListener {
                        viewModel.singleSelect(-1)
                    }
                }
            }
            binding.root.setOnLongClickListener {
                if (!viewModel.isReadyOnly && !viewModel.isInMultiSelectionMode) {
                    viewModel.toggleMultiSelection(currentList[adapterPosition])
                }
                return@setOnLongClickListener true
            }
            binding.ibAction.setAntiMoneyClickListener {
                val applet = currentList[adapterPosition]
                when (it.tag as? Int) {
                    FlowItemViewBinder.ACTION_COLLAPSE -> {
                        viewModel.toggleCollapse(applet)
                        notifyItemChanged(adapterPosition)
                    }
                    FlowItemViewBinder.ACTION_INVERT -> {
                        applet.toggleInversion()
                        notifyItemChanged(adapterPosition)
                    }
                    FlowItemViewBinder.ACTION_EDIT -> {
                        menuHelper.onFlowMenuItemClick(it, applet, R.id.item_edit)
                    }
                    FlowItemViewBinder.ACTION_ADD -> {
                        menuHelper.onFlowMenuItemClick(it, applet, R.id.item_add_inside)
                    }
                    FlowItemViewBinder.ACTION_ENTER -> {
                        val dialog = FlowEditorDialog().setFlow(
                            applet as Flow, viewModel.isSelectingRef
                        ).doOnCompletion { edited ->
                            // We don't need to replace the flow, just refilling it is ok
                            applet.clear()
                            applet.addAll(edited)
                            viewModel.regenerateApplets()
                            viewModel.onAppletChanged.value = applet
                        }.doSplit {
                            viewModel.splitContainerFlow(applet)
                        }
                        if (viewModel.isSelectingRef) {
                            dialog.doOnReferenceSelected(viewModel.doOnRefSelected)
                            dialog.setReferenceToSelect(
                                viewModel.refSelectingApplet, viewModel.refValueDescriptor, null
                            )
                        }
                        dialog.show(fragment.childFragmentManager)
                    }
                }
            }
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlowViewHolder {
        return FlowViewHolder(ItemFlowItemBinding.inflate(layoutInflater, parent, false))
    }

    override fun onBindViewHolder(holder: FlowViewHolder, position: Int) {
        itemViewBinder.bindViewHolder(holder, currentList[position])
    }
}