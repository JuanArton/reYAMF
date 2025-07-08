package com.mja.reyamf.xposed.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.res.Resources
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.common.onException
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.xposed.services.YAMFManager
import de.robv.android.xposed.XposedBridge
import com.mja.reyamf.common.model.Config as YAMFConfig

fun log(tag: String, message: String) {
    XposedBridge.log("[$tag] $message")
}

fun log(tag: String, message: String, t: Throwable) {
    XposedBridge.log("[$tag] $message")
    XposedBridge.log(t)
}

@SuppressLint("MissingPermission")
fun moveTask(taskId: Int, displayId: Int) {
    Instances.activityTaskManager.moveRootTaskToDisplay(taskId, displayId)
    Instances.activityManager.moveTaskToFront(taskId, 0)
}

fun Number.dpToPx() =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics
    )

val emptyContextParams = ContextParams.Builder().build()

fun Context.createContext() = createContext(emptyContextParams)

fun startActivity(context: Context, componentName: ComponentName, userId: Int, displayId: Int) {
    context.invokeMethod(
        "startActivityAsUser",
        args(
            Intent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = componentName
                `package` = component!!.packageName
                action = Intent.ACTION_VIEW
            },
            ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
                this.invokeMethod("setCallerDisplayId", args(displayId), argTypes(Integer.TYPE))
            }.toBundle(),
            UserHandle::class.java.newInstance(
                args(userId),
                argTypes(Integer.TYPE)
            )
        ), argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
    )
}

fun moveToDisplay(context: Context, taskId: Int, componentName: ComponentName, userId: Int, displayId: Int) {
    when (YAMFManager.config.windowfy) {
        0 -> {
            runCatching {
                moveTask(taskId, displayId)
            }.onException {
                TipUtil.showToast("Unable to move task $taskId")
            }
        }
        1 -> {
            runCatching {
                startActivity(context, componentName, userId, displayId)
            }.onException {
                TipUtil.showToast("Unable to start activity $componentName")
            }
        }
        2 -> {
            runCatching {
                moveTask(taskId, displayId)
            }.onException {
                TipUtil.showToast("Unable to move task $taskId")
                runCatching {
                    startActivity(context, componentName, userId, displayId)
                }.onException {
                    TipUtil.showToast("Unable to start activity $componentName")
                }
            }
        }
    }
}

fun StartCmd.startAuto(displayId: Int) {
    when {
        canStartActivity && canMoveTask ->
            moveToDisplay(Instances.systemContext, taskId!!, componentName!!, userId!!, displayId)
        canMoveTask -> {
            runCatching {
                moveTask(taskId!!, displayId)
            }.onException {
                TipUtil.showToast("can't move task $taskId")
            }
        }
        canStartActivity -> {
            runCatching {
                startActivity(Instances.systemContext, componentName!!, userId!!, displayId)
            }.onException {
                TipUtil.showToast("can't start activity $componentName")
            }
        }
    }
}

fun getTopRootTask(displayId: Int): ActivityTaskManager.RootTaskInfo? {
    Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
        if (task.visible)
            return task
    }
    return null
}

fun Context.registerReceiver(action: String, onReceive: BroadcastReceiver.(Context, Intent) -> Unit) {
    registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReceive(this, context, intent)
        }
    }, android.content.IntentFilter(action), Context.RECEIVER_EXPORTED)
}


val ActivityInfo.componentName: ComponentName
    get() = ComponentName(packageName, name)

fun IPackageManagerHidden.getActivityInfoCompat(className: ComponentName, flags: Int, userId: Int): ActivityInfo =
    getActivityInfo(className, flags.toLong(), userId)

fun vibratePhone(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE))
}

fun animateResize(
    view: View,
    startWidth: Int,
    endWidth: Int,
    startHeight: Int,
    endHeight: Int,
    context: Context,
    baseDuration: Long = 300L,
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f // fallback to normal scale if not found
    }

    val config = try {
        gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
    } catch (e: Exception) {
        gson.fromJson(YAMFManager.configJson, YAMFConfig::class.java)
    }
    val adjustedDuration = (if (config.animationSpeed < 5100) config.animationSpeed else 300).toLong()

    val widthAnimator = ValueAnimator.ofInt(startWidth, endWidth).apply {
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            val params = view.layoutParams
            params.width = value
            view.layoutParams = params
        }
    }

    val heightAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
        addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            val params = view.layoutParams
            params.height = value
            view.layoutParams = params
        }
    }

    AnimatorSet().apply {
        playTogether(widthAnimator, heightAnimator)
        duration = adjustedDuration
        interpolator = AccelerateDecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onEnd?.invoke()
            }
        })
        start()
    }
}

fun animateScaleThenResize(
    view: View,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    pivotX: Float,
    pivotY: Float,
    endWidth: Int,
    endHeight: Int,
    context: Context,
    baseDuration: Long = 300L,
    onEnd: (() -> Unit)? = null
) {
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (e: Settings.SettingNotFoundException) {
        1.0f
    }

    val config = try {
        gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
    } catch (e: Exception) {
        gson.fromJson(YAMFManager.configJson, YAMFConfig::class.java)
    }
    val adjustedDuration = (if (config.animationSpeed < 5100) config.animationSpeed else 300 * scale).toLong()

    val scaleAnimation = ScaleAnimation(
        startX, endX,
        startY, endY,
        Animation.RELATIVE_TO_SELF, pivotX,
        Animation.RELATIVE_TO_SELF, pivotY
    ).apply {
        duration = adjustedDuration
        fillAfter = false
        interpolator = AccelerateDecelerateInterpolator()
        setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                val params = view.layoutParams
                params.width = endWidth
                params.height = endHeight
                view.layoutParams = params
                onEnd?.invoke()
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })
    }

    view.startAnimation(scaleAnimation)
}


fun animateAlpha(view: View, startAlpha: Float, endAlpha: Float, onEnd: (() -> Unit)? = null) {
    if (endAlpha == 1F) view.visibility = View.VISIBLE
    val animation1 = AlphaAnimation(startAlpha, endAlpha)
    animation1.duration = 300

    animation1.setAnimationListener(object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation?) {}

        override fun onAnimationEnd(animation: Animation?) {
            onEnd?.invoke()
        }

        override fun onAnimationRepeat(animation: Animation?) {}
    })

    view.startAnimation(animation1)
    if (endAlpha == 1F) view.visibility = View.VISIBLE else view.visibility = View.GONE
}