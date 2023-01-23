/*
 * Copyright (c) 2022 xjunz. All rights reserved.
 */

package top.xjunz.tasker.task.applet.option

import android.annotation.SuppressLint
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import top.xjunz.shared.ktx.casted
import top.xjunz.tasker.R
import top.xjunz.tasker.app
import top.xjunz.tasker.engine.applet.base.Applet
import top.xjunz.tasker.ktx.*
import top.xjunz.tasker.task.applet.option.descriptor.ArgumentDescriptor
import top.xjunz.tasker.task.applet.option.descriptor.ValueDescriptor
import top.xjunz.tasker.util.Router.launchAction
import java.util.*

/**
 * The entity describing an applet's information. You can call [yield] to create an [Applet]
 * as per the option.
 *
 * @author xjunz 2022/09/22
 */
class AppletOption(
    val registryId: Int,
    private val titleResource: Int,
    private val invertedTitleRes: Int,
    private inline val rawCreateApplet: () -> Applet
) : Comparable<AppletOption> {

    companion object {

        const val ACTION_TOGGLE_RELATION = "AO_TOGGLE_REL"
        const val ACTION_NAVIGATE_REFERENCE = "AO_NAVI_REF"
        const val ACTION_EDIT_VALUE = "AO_EDIT_VAL"

        /**
         * Indicate that the inverted title of an option is auto-generated.
         *
         * @see AppletOption.invertedTitle
         */
        const val TITLE_AUTO_INVERTED = 0

        const val TITLE_NONE = -1

        private val DEFAULT_DESCRIBER: (Applet?, Any?) -> CharSequence? = { _, value ->
            if (value is Boolean) {
                if (value) R.string._true.text else R.string._false.text
            } else {
                value?.toString()
            }
        }

        private val DEFAULT_RANGE_FORMATTER: (Applet?, Any?) -> CharSequence? = { _, range ->
            range as Collection<*>
            check(range.size == 2)
            val first = range.firstOrNull()
            val last = range.lastOrNull()
            check(first != null || last != null)
            if (first == last) {
                first.toString()
            } else if (first == null) {
                R.string.format_less_than.format(last)
            } else if (last == null) {
                R.string.format_larger_than.format(first)
            } else {
                R.string.format_range.format(first, last)
            }
        }

        fun makeRelationSpan(origin: CharSequence, applet: Applet, isCriterion: Boolean)
                : CharSequence {
            val relation = if (isCriterion) {
                if (applet.isAnd) R.string._and.str else R.string._or.str
            } else {
                if (applet.isAnd) R.string.on_success.str else R.string.on_failure.str
            }
            return relation.clickable {
                app.launchAction(ACTION_TOGGLE_RELATION, applet.hashCode())
            }.bold().underlined() + origin
        }

        private fun makeReferenceText(applet: Applet, name: CharSequence?): CharSequence? {
            if (name == null) return null
            return name.clickable {
                app.launchAction(
                    ACTION_NAVIGATE_REFERENCE,
                    "$name" + Char(0) + applet.hashCode()
                )
            }.foreColored().backColored().underlined()
        }

    }

    var appletId: Int = -1
        set(value) {
            check(value == field || field == -1) {
                "appletId is already set. Pls do not set again!"
            }
            field = value
        }

    private var describer: (Applet?, Any?) -> CharSequence? = DEFAULT_DESCRIBER

    private var helpTextRes: Int = -1

    var helpText: CharSequence? = null
        get() = if (helpTextRes != -1) helpTextRes.text else field
        private set

    /**
     * Whether this is an valid option able to yield an applet.
     */
    val isValid get() = appletId != -1

    /**
     * The category id identifying the option's category and its position in the category.
     *
     */
    var categoryId: Int = -1

    /**
     * As per [Applet.isInverted].
     */
    var isInverted = false

    /**
     * As per [Applet.isInvertible].
     */
    val isInvertible get() = invertedTitleRes != TITLE_NONE

    /**
     * As per [Applet.value].
     */
    var value: Any? = null

    var minApiLevel: Int = -1

    /**
     * The index in all categories.
     */
    val categoryIndex: Int get() = categoryId ushr 8

    var presetsNameRes: Int = -1
        private set

    var presetsValueRes: Int = -1
        private set

    val hasPresets get() = presetsNameRes != -1 && presetsValueRes != -1

    var descAsTitle: Boolean = false
        private set

    var isTitleComposite: Boolean = false
        private set

    var isShizukuOnly = false
        private set

    var isA11yOnly = false
        private set

    var arguments: List<ArgumentDescriptor> = emptyList()

    var results: List<ValueDescriptor> = emptyList()

    val rawTitle: CharSequence?
        get() = if (titleResource == TITLE_NONE) null else titleResource.text

    // TODO
    var valueChecker: ((Any?) -> String?)? = null

    /**
     * The applet's value is innate and hence not modifiable.
     */
    var isValueInnate: Boolean = false
        private set

    private var titleModifierRes: Int = -1

    var titleModifier: String? = null
        get() = if (titleModifierRes != -1) titleModifierRes.str else field
        private set

    private val invertedTitleResource: Int by lazy {
        @SuppressLint("DiscouragedApi")
        when (invertedTitleRes) {
            TITLE_AUTO_INVERTED -> {
                val invertedResName = "not_" + app.resources.getResourceEntryName(titleResource)
                val id = app.resources.getIdentifier(invertedResName, "string", app.packageName)
                check(id != 0) { "Resource id 'R.string.$invertedResName' not found!" }
                id
            }
            TITLE_NONE -> TITLE_NONE
            else -> invertedTitleRes
        }
    }

    private val invertedTitle: CharSequence?
        get() = if (invertedTitleResource == TITLE_NONE) null else invertedTitleResource.text

    val dummyTitle get() = loadTitle(null, isInverted)

    fun findResults(descriptor: ValueDescriptor): List<ValueDescriptor> {
        return results.filter {
            it.type == descriptor.type && it.variantType == descriptor.variantType
        }
    }

    private fun loadTitle(applet: Applet?, isInverted: Boolean): CharSequence? {
        if (isTitleComposite) {
            return composeTitle(applet)
        }
        return if (isInverted) invertedTitle else rawTitle
    }

    fun loadTitle(applet: Applet): CharSequence? {
        return loadTitle(applet, applet.isInverted)
    }

    fun shizukuOnly(): AppletOption {
        isShizukuOnly = true
        return this
    }

    fun descAsTitle(): AppletOption {
        descAsTitle = true
        return this
    }

    fun withValue(value: Any?): AppletOption {
        this.value = value
        return this
    }

    fun hasInnateValue(): AppletOption {
        isValueInnate = true
        return this
    }

    fun describe(applet: Applet): CharSequence? = describer(applet, applet.value)

    val rawDescription: CharSequence?
        get() = if (value == null) null else describer(null, value!!)

    fun toggleInversion() {
        isInverted = !isInverted
    }

    fun withDefaultRangeDescriber(): AppletOption {
        describer = DEFAULT_RANGE_FORMATTER
        return this
    }

    fun yield(): Applet {
        check(isValid) {
            "Invalid applet option unable to yield an applet!"
        }
        return rawCreateApplet().also {
            it.id = registryId shl 16 or appletId
            it.isInverted = isInverted
            it.isInvertible = isInvertible
            if (!isValueInnate) it.value = value
        }
    }

    fun <T : Any> withValueDescriber(block: (T) -> CharSequence): AppletOption {
        describer = { _, value ->
            if (value == null) {
                null
            } else {
                block(value.casted())
            }
        }
        return this
    }

    fun <T : Any> withDescriber(block: (Applet, T?) -> CharSequence?): AppletOption {
        describer = { applet, value ->
            if (applet == null) {
                null
            } else {
                block(applet, value?.casted())
            }
        }
        return this
    }

    private fun composeTitle(applet: Applet?): CharSequence {
        if (applet == null) {
            return titleResource.format(*Array(arguments.size) {
                arguments[it].substitution
            })
        }
        val res = if (applet.isInverted) invertedTitleResource else titleResource
        val split = res.str.split("%s")
        var title: CharSequence = split[0]
        for (i in 1..split.lastIndex) {
            val s = split[i]
            val index = i - 1
            val arg = arguments[index]
            val ref = applet.references[index]
            val sub = when {
                arg.isReferenceOnly -> makeReferenceText(applet, ref) ?: arg.substitution
                arg.isValueOnly -> arg.substitution
                else -> when {
                    value == null && ref == null -> arg.substitution
                    value == null && ref != null -> makeReferenceText(applet, ref)!!
                    ref == null -> arg.substitution
                    else -> error("Value and reference both specified!")
                }
            }
            title += sub + s
        }
        return title
    }

    fun restrictApiLevel(minApiLevel: Int): AppletOption {
        this.minApiLevel = minApiLevel
        return this
    }

    fun hasCompositeTitle(): AppletOption {
        isTitleComposite = true
        return this
    }

    /**
     * Only for text type options. Set its preset text array resource.
     */
    fun withPresetArray(@ArrayRes nameRes: Int, @ArrayRes valueRes: Int): AppletOption {
        presetsNameRes = nameRes
        presetsValueRes = valueRes
        return this
    }

    inline fun <reified T> withResult(
        @StringRes name: Int,
        variantType: Int = -1
    ): AppletOption {
        if (results == Collections.EMPTY_LIST) results = mutableListOf()
        (results as MutableList<ValueDescriptor>).add(
            ValueDescriptor(name, T::class.java, variantType)
        )
        return this
    }

    inline fun <reified T> withArgument(
        @StringRes name: Int,
        variantType: Int = -1,
        isRef: Boolean? = null,
        @StringRes substitution: Int = -1,
    ): AppletOption {
        if (arguments == Collections.EMPTY_LIST) arguments = mutableListOf()
        (arguments as MutableList<ArgumentDescriptor>).add(
            ArgumentDescriptor(name, substitution, T::class.java, variantType, isRef)
        )
        return this
    }

    /**
     * Describe [Applet.value] as an argument.
     */
    inline fun <reified T> withValueArgument(
        @StringRes name: Int,
        variantType: Int = -1
    ): AppletOption {
        return withArgument<T>(name, variantType, false)
    }

    inline fun <reified T> withRefArgument(
        @StringRes name: Int,
        variantType: Int = -1,
        @StringRes substitution: Int = -1,
    ): AppletOption {
        return withArgument<T>(name, variantType, true, substitution)
    }

    inline fun <T : Any> withValueChecker(crossinline checker: (T?) -> String?): AppletOption {
        valueChecker = {
            checker(it?.casted())
        }
        return this
    }

    fun withHelperText(@StringRes res: Int): AppletOption {
        helpTextRes = res
        helpText = null
        return this
    }

    fun withTitleModifier(@StringRes res: Int): AppletOption {
        titleModifierRes = res
        return this
    }

    fun withTitleModifier(text: String): AppletOption {
        titleModifier = text
        titleModifierRes = -1
        return this
    }

    fun withHelperText(text: CharSequence): AppletOption {
        helpText = text
        helpTextRes = -1
        return this
    }

    override fun compareTo(other: AppletOption): Int {
        check(registryId == other.registryId) {
            "Only applets with the same factory id are comparable!"
        }
        check(categoryId > -1)
        return categoryId.compareTo(other.categoryId)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppletOption

        if (appletId != other.appletId) return false
        if (registryId != other.registryId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = appletId
        result = 31 * result + registryId
        return result
    }
}