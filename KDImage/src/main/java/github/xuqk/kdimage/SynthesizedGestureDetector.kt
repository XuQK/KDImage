package github.xuqk.kdimage

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Author: XuQK
 * site: https://github.com/XuQK
 * Email: xuqiankun66@gmail.com
 *
 * Created Time: 2023/2/17 11:07
 */

/**
 * 综合手势探测，包括双击，长按，点击，拖拽，手势缩放
 */
suspend fun PointerInputScope.detectSynthesizedGestures(
    onDoubleTap: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
    onDragStart: (position: Offset, prevPosition: Offset) -> Unit = { _, _ -> },
    onDragEnd: (velocity: Velocity) -> Unit = { _ -> },
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit = { _, _ -> },
    onGesture: (event: PointerEvent, centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit = { _, _, _, _, _ -> },
    onGestureEndOrCancel: () -> Unit = {},
    onFirstDown: () -> Unit = {},
) = coroutineScope {
    // 双击事件是否正在检测中
    var tapJob: Job? = null
    var longPressJob: Job? = null
    val doubleTapDetector = DoubleTapDetector(viewConfiguration)
    // 拖动是否要被缩放代替，此值在双击第二次按下到抬起手指之间，触发 drag 条件时，为 true，此时移动手指，原本的拖动变为缩放
    var dragMapToZoom = false
    var gestureHandled = false
    var longPressed = false
    var dragMapToZoomCenter = Offset.Zero
    var dragging = false
    val velocityTracker = VelocityTracker()
    forEachGesture {
        awaitPointerEventScope {
            val firstDown = awaitFirstDown()
            onFirstDown()
            log("firstDown: $firstDown")
            // 新一轮事件开始，重置所有标志和正在等待中的点击任务
            tapJob?.cancel()
            tapJob = null
            longPressJob?.cancel()
            longPressJob = null
            longPressed = false
            dragMapToZoom = false
            gestureHandled = false
            dragMapToZoomCenter = Offset.Zero
            dragging = false
            velocityTracker.resetTracking()
            velocityTracker.addPosition(firstDown.uptimeMillis, firstDown.position)
            longPressJob = launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                log("longPress: ${firstDown.position}")
                longPressed = true
                onLongPress?.invoke(firstDown.position)
            }
            if (doubleTapDetector.onDown(firstDown)) {
                // 检测到此按下时间处于双击序列中，将 drag 映射为 zoom
                dragMapToZoom = true
                dragMapToZoomCenter = firstDown.position
                // 且需要取消长按
                longPressJob?.cancel()
                longPressJob = null
            }

            while (true) {
                val event = awaitPointerEvent()
//                event.changes.forEach {
//                    Log.d("SynthesizedGesture", "触摸事件：${it.toString()}")
//                }
                if (longPressed) {
                    // 检测到已经长按过了，直接截断事件链
                    return@awaitPointerEventScope
                }
                // 先判断是否需要被手势接管，如果触摸点大于1，或者触摸点滑动距离大于 touchSlop，表明需要被手势接管
                if (event.changes.size > 1 || event.changes[0]
                        .positionChange()
                        .getDistanceSquared() > viewConfiguration.touchSlop
                ) {
                    gestureHandled = true
                }
                if (gestureHandled) {
                    if (event.changes.size == 1 && !event.changes[0].pressed) {
                        val velocity = velocityTracker.calculateVelocity()
                        log("dragEnd velocity = $velocity")
                        onDragEnd(velocity)
                        log("gestureEndOrCancel")
                        onGestureEndOrCancel()
                        velocityTracker.resetTracking()
                        return@awaitPointerEventScope
                    }
                    if (event.changes.size > 1) {
                        tapJob?.cancel()
                        tapJob = null
                        longPressJob?.cancel()
                        longPressJob = null
                        gestureHandled = true
                        // 缩放
                        val centroid = event.calculateCentroid()
                        if (centroid != Offset.Unspecified) {
                            val zoomChange = event.calculateZoom()
                            val rotationChange = event.calculateRotation()
                            val panChange = event.calculatePan()
                            log("zoom: centroid = $centroid, panChange = $panChange, zoomChange = $zoomChange, rotationChange = $rotationChange")
                            onGesture(
                                event,
                                centroid,
                                panChange,
                                zoomChange,
                                rotationChange
                            )
                        }
                        continue
                    } else if (event.changes[0]
                            .positionChange()
                            .getDistanceSquared() > viewConfiguration.touchSlop
                    ) {
                        tapJob?.cancel()
                        tapJob = null
                        longPressJob?.cancel()
                        longPressJob = null
                        gestureHandled = true
                        val pointer = event.changes[0]
                        // 拖拽
                        if (dragMapToZoom) {
                            val zoomChange =
                                (viewConfiguration.touchSlop + (pointer.position.y - pointer.previousPosition.y) / 10) / viewConfiguration.touchSlop
                            log("dragMapToZoom: centroid = $dragMapToZoomCenter, panChange = ${Offset.Zero}, zoomChange = $zoomChange, rotationChange = 0")
                            onGesture(event, dragMapToZoomCenter, Offset.Zero, zoomChange, 0f)
                        } else {
                            log("drag: amount = ${pointer.positionChange()}  ${pointer.isConsumed}")
                            if (!dragging) {
                                dragging = true
                                onDragStart(pointer.position, pointer.previousPosition)
                            }
                            velocityTracker.addPosition(pointer.uptimeMillis, pointer.position)
                            onDrag(pointer, pointer.positionChange())
                        }
                        continue
                    }
                } else {
                    // 双击单击
                    val pointer = event.changes[0]
                    if (!pointer.pressed) {
                        if (doubleTapDetector.onUp(pointer)) {
                            // 检测到是双击，取消单击
                            tapJob?.cancel()
                            tapJob = null
                            longPressJob?.cancel()
                            longPressJob = null
                            log("doubleTap: ${pointer.position}")
                            onDoubleTap?.invoke(pointer.position)
                        } else if (pointer.id == firstDown.id && pointer.uptimeMillis - firstDown.uptimeMillis < viewConfiguration.doubleTapTimeoutMillis) {
                            // 单击预备
                            tapJob = launch {
                                delay(viewConfiguration.doubleTapTimeoutMillis)
                                longPressJob?.cancel()
                                longPressJob = null
                                log("tap: ${pointer.position}")
                                onTap?.invoke(pointer.position)
                            }
                        }
                        return@awaitPointerEventScope
                    }
                }
            }
        }
    }
}

/**
 * 双击探测工具类，只有当两次点击的 down 和 up 时间都发生，且邻近两次事件的时差在 [ViewConfiguration.doubleTapTimeoutMillis] 之内，才认为是双击
 */
class DoubleTapDetector(private val viewConfig: ViewConfiguration) {
    /**
     * 事件栈，从第一位开始分别是，firstDown, firstUp, secondDown, secondUp
     * 当 secondUp 被填入的时候，可以进行双击检测，如果四个时间间隔都在限制内，认为发生了双击
     * 当 secondDown 被摊入的时候，可以进行双击长按检测，如果三个时间都在限制内，被认为是双击，且第二次点击没有 up，进行了长按
     */
    private var doubleTapUptimeStack = mutableListOf(0L, 0L, 0L, 0L)

    /**
     * @return true 表示正在双击检测过程中，此返回值用来判断 drag 是否需要映射成 zoom 行为
     */
    fun onDown(pointer: PointerInputChange): Boolean {
        doubleTapUptimeStack.add(pointer.uptimeMillis)
        doubleTapUptimeStack.removeAt(0)
        return (doubleTapUptimeStack[3] - doubleTapUptimeStack[2] < viewConfig.doubleTapTimeoutMillis) &&
            (doubleTapUptimeStack[2] - doubleTapUptimeStack[1] < viewConfig.doubleTapTimeoutMillis)
    }

    /**
     * @return true 表示检测到双击
     */
    fun onUp(pointer: PointerInputChange): Boolean {
        doubleTapUptimeStack.add(pointer.uptimeMillis)
        doubleTapUptimeStack.removeAt(0)
        return (doubleTapUptimeStack[3] - doubleTapUptimeStack[2] < viewConfig.doubleTapTimeoutMillis) &&
            (doubleTapUptimeStack[2] - doubleTapUptimeStack[1] < viewConfig.doubleTapTimeoutMillis) &&
            (doubleTapUptimeStack[1] - doubleTapUptimeStack[0] < viewConfig.doubleTapTimeoutMillis)
    }
}
