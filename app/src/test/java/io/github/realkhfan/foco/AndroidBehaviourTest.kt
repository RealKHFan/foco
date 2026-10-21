package io.github.realkhfan.foco

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.realkhfan.foco.media.GalleryIntents
import io.github.realkhfan.foco.media.MediaItem
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.TimerSetting
import io.github.realkhfan.foco.ui.Capabilities
import io.github.realkhfan.foco.ui.ProState
import io.github.realkhfan.foco.ui.ProTool
import io.github.realkhfan.foco.ui.Readout
import io.github.realkhfan.foco.ui.nightWillRun
import io.github.realkhfan.foco.ui.toolModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidBehaviourTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val photo = MediaItem(id = 4242, isVideo = false, mimeType = "image/jpeg")
    private val video = MediaItem(id = 77, isVideo = true, mimeType = "video/mp4")

    // ------------------------------------------------------------ gallery button

    @Test fun `media uri carries the media store id`() {
        assertEquals("content://media/external/images/media/4242", photo.uri.toString())
        assertEquals("content://media/external/video/media/77", video.uri.toString())
    }

    @Test fun `google photos intent is the same one pixel camera sends`() {
        val intent = GalleryIntents.googlePhotos(photo)
        assertEquals("com.android.camera.action.REVIEW", intent.action)
        assertEquals("com.google.android.apps.photos", intent.`package`)
        assertEquals(photo.uri, intent.data)
        assertEquals("image/jpeg", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `opens google photos when it is installed`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        val photos = ComponentName(GalleryIntents.GOOGLE_PHOTOS, "com.google.android.apps.photos.pager.HostPhotoPagerActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(photos)
        pm.addIntentFilterForActivity(photos, IntentFilter(GalleryIntents.ACTION_REVIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("image/*")
        })
        var viewer = false
        val target = GalleryIntents.open(activity, photo) { viewer = true }
        assertEquals(GalleryIntents.Target.GOOGLE_PHOTOS, target)
        assertFalse(viewer)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GalleryIntents.GOOGLE_PHOTOS, started.`package`)
        assertEquals("content://media/external/images/media/4242", started.dataString)
    }

    @Test fun `without google photos the swipeable viewer opens even if another gallery exists`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        val gallery = ComponentName("org.fossify.gallery", "org.fossify.gallery.ViewPagerActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(gallery)
        pm.addIntentFilterForActivity(gallery, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("image/*")
        })
        var viewer = false
        val target = GalleryIntents.open(activity, photo) { viewer = true }
        assertEquals(GalleryIntents.Target.BUILT_IN, target)
        assertTrue(viewer)
    }

    @Test fun `night slider maps off auto and seconds`() {
        val caps = Capabilities()
        val auto = toolModel(ProTool.NIGHT, caps, ProState(), Readout(), nightEstimate = 2.2f)
        assertEquals("3 s", auto.bubble)
        assertTrue(auto.isAuto)
        assertEquals(0f, auto.apply(0).nightSeconds)
        assertEquals(null, auto.apply(5).nightSeconds)
        assertEquals(4f, auto.apply(7).nightSeconds)
        assertEquals("Off", toolModel(ProTool.NIGHT, caps, ProState(nightSeconds = 0f), Readout()).bubble)
        assertEquals("1.5 s", toolModel(ProTool.NIGHT, caps, ProState(nightSeconds = 1.5f), Readout()).bubble)
    }

    @Test fun `night runs automatically only in the dark`() {
        val settings = AppSettings(app)
        settings.flash = FlashSetting.NIGHT_AUTO
        val s = io.github.realkhfan.foco.ui.AppState().apply { processing = true }
        assertFalse(s.nightWillRun(settings))
        s.dark = true
        assertTrue(s.nightWillRun(settings)) // default "More light: Night auto"
        s.pro = ProState(nightSeconds = 0f)
        assertFalse(s.nightWillRun(settings))
        s.pro = ProState()
        settings.flash = FlashSetting.OFF
        assertFalse(s.nightWillRun(settings))
        s.photoMode = io.github.realkhfan.foco.ui.PhotoMode.NIGHT
        assertTrue(s.nightWillRun(settings))
    }

    @Test fun `uses the built-in viewer when there is no gallery at all`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        var viewer = false
        assertEquals(GalleryIntents.Target.BUILT_IN, GalleryIntents.open(activity, photo) { viewer = true })
        assertTrue(viewer)
    }

    // ------------------------------------------------------------ settings

    @Test fun `settings survive a restart`() {
        val first = AppSettings(app)
        first.flash = FlashSetting.ON
        first.timer = TimerSetting.S5
        first.raw = true
        val second = AppSettings(app)
        assertEquals(FlashSetting.ON, second.flash)
        assertEquals(TimerSetting.S5, second.timer)
        assertTrue(second.raw)
    }

    // ------------------------------------------------------------ pro controls

    @Test fun `pro sliders map to camera values`() {
        val caps = Capabilities(
            evMin = -12, evMax = 12, evStep = 1f / 6f, manualSensor = true, manualFocus = true, minFocusDiopters = 10f,
            isoMin = 50, isoMax = 6400, shutterMinNs = 100_000L, shutterMaxNs = 1_000_000_000L, tonemapCurve = true,
            awbModes = setOf(0, 1, 2, 5),
        )
        val pro = ProState()
        val brightness = toolModel(ProTool.BRIGHTNESS, caps, pro, Readout())
        assertEquals(24, brightness.steps)
        assertEquals(12, brightness.index)
        assertEquals("-2", brightness.labels.first().second)
        assertEquals(4, brightness.apply(16).evIndex)

        val wb = toolModel(ProTool.WHITE_BALANCE, caps, pro, Readout())
        assertEquals("Auto", wb.bubble)
        assertTrue(wb.isAuto)
        assertEquals(2300, wb.apply(0).wbKelvin)

        val iso = toolModel(ProTool.ISO, caps, pro, Readout(iso = 400))
        assertEquals("Auto", iso.bubble)
        assertEquals(400, iso.apply(iso.index).iso)

        val focus = toolModel(ProTool.FOCUS, caps, pro.copy(focusDiopters = 0f), Readout())
        assertEquals("∞", focus.bubble)
        assertNull(focus.reset.focusDiopters)

        val noManual = toolModel(ProTool.SHUTTER, Capabilities(), pro, Readout())
        assertFalse(noManual.enabled)
    }

    // ------------------------------------------------------------ app start (no real camera here)

    @Test fun `app starts and asks for the camera`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val request = shadowOf(activity).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(Manifest.permission.CAMERA))
        controller.pause().stop().destroy()
    }

    @Test fun `app starts with permission and survives having no camera`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `other apps can ask for a photo`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).setClass(app, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `lock screen camera starts`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).setClass(app, SecureCameraActivity::class.java)
        val controller = Robolectric.buildActivity(SecureCameraActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
}
