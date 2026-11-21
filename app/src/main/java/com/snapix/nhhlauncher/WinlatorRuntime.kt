package com.snapix.nhhlauncher
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.winlator.XServerDisplayActivity
import com.winlator.box64.Box64Preset
import com.winlator.container.AudioDrivers
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.container.GraphicsDrivers
import com.winlator.core.WineThemeManager
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

data class LauncherRuntimeSettings(
 val resolution:String="1280x720",val vulkanDriver:String=GraphicsDrivers.DEFAULT_VULKAN_DRIVER,val openGlDriver:String=GraphicsDrivers.DEFAULT_OPENGL_DRIVER,
 val dxWrapper:String=DXWrappers.DXVK,val audioDriver:String=AudioDrivers.ALSA,val box64Preset:String=Box64Preset.DEFAULT,val wineEsync:Boolean=true,
 val showFpsHud:Boolean=false,val forceFullscreen:Boolean=true,val onScreenControls:Boolean=true,val preferredInputApi:String="XINPUT",
 val overlayOpacity:Float=.55f,val controlScale:Float=1f,val stickDeadzone:Float=.12f)

object WinlatorRuntime {
 const val PREF_CONTAINER_ID="winlator_container_id"
 private const val PREF_GAME_PATH="game_exe"
 private const val PREF_PROFILE="nhh_controls_profile_id"
 private const val PREF_FULLSCREEN="nhh_force_fullscreen"
 private const val NAME="NHH"
 private val lock=Mutex()
 private val rootVersion=RootFSInstaller.LATEST_VERSION.toInt()
 private var crashInstalled=false

 fun installCrashRecorder(context:Context){if(crashInstalled)return;synchronized(this){if(crashInstalled)return
  val app=context.applicationContext
  val old=Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler{thread,t->NhhControllerRuntime.detachFromSession();appendLaunchLog(app,"UNCAUGHT "+thread.name+":\n"+Log.getStackTraceString(t));old?.uncaughtException(thread,t)}
  crashInstalled=true}}

 suspend fun prepare(a:MainActivity,e:File,p:SharedPreferences,cb:(Float,String)->Unit)=ensureReady(a,e,p,cb)

 suspend fun ensureReady(a:MainActivity,e:File,p:SharedPreferences,cb:(Float,String)->Unit,forceRebuild:Boolean=false)=lock.withLock{
  val rootDir=File(a.filesDir,"game").canonicalFile
  val exe=e.canonicalFile
  require(exe.isFile){"The selected executable no longer exists."}
  require(exe.path.startsWith(rootDir.path+File.separator)){"The executable is outside the game folder."}
  var r=RootFS.find(a)
  if(!r.isValid()||r.getVersion()<rootVersion){
   cb(.02f,"Installing system files…");RootFSInstaller.installIfNeeded(a);var waited=0L
   while(true){delay(300);waited+=300;r=RootFS.find(a);val x=RootFSInstaller.getNhhInstallProgress()
    if(r.isValid()&&r.getVersion()>=rootVersion)break
    if(x<0)throw IllegalStateException("Winlator system-file installation failed.")
    if(waited>900000)throw IllegalStateException("Winlator bootstrap timed out.")
    cb(if(x>0)(x/100f).coerceIn(.02f,.38f)else .02f,if(x>0)"Installing system files… "+x+"%" else "Installing system files…")}}
  cb(.4f,"Preparing NHH Xbox controller profile…")
  val profile=NhhControllerRuntime.ensureNhhProfile(a)
  val m=ContainerManager(a);val oldId=p.getInt(PREF_CONTAINER_ID,0);val old=if(oldId>0)m.getContainerById(oldId)else null
  if(!forceRebuild&&old!=null){mapGameDrive(old,rootDir);old.saveData();p.edit().putString(PREF_GAME_PATH,exe.absolutePath).putInt(PREF_PROFILE,profile.id).apply();cb(1f,"Winlator container verified.");return@withLock}
  cb(.55f,"Creating the NHH Winlator container…")
  val c=createContainer(m,a);mapGameDrive(c,rootDir);c.saveData()
  p.edit().putInt(PREF_CONTAINER_ID,c.id).putString(PREF_GAME_PATH,exe.absolutePath).putInt(PREF_PROFILE,profile.id).apply()
  if(old!=null)m.removeContainerAsync(old){}
  cb(1f,"Winlator container created. NHH is ready to launch.")
 }

 private fun mapGameDrive(c:Container,root:File){
  val x=c.drivesIterator().filterNot{it.letter.equals("G",true)}.map{it.letter+":"+it.path}.toMutableList()
  x.add("G:"+root.canonicalPath);c.setDrives(x.joinToString(""))
 }

 private suspend fun createContainer(m:Container,ctx:Context):Container=suspendCancellableCoroutine{co->
  val d=JSONObject().apply{
   put("name",NAME);put("screenSize",Container.DEFAULT_SCREEN_SIZE);put("envVars",Container.DEFAULT_ENV_VARS)
   put("cpuList","");put("cpuListWoW64","");put("graphicsDriver",GraphicsDrivers.getDefaultDriver(ctx))
   put("dxwrapper",Container.DEFAULT_DXWRAPPER);put("audioDriver",Container.DEFAULT_AUDIO_DRIVER)
   put("wincomponents",Container.DEFAULT_WINCOMPONENTS);put("drives",Container.DEFAULT_DRIVES);put("hudMode",0)
   put("startupSelection",Container.STARTUP_SELECTION_ESSENTIAL);put("box64Preset",Box64Preset.DEFAULT);put("desktopTheme",WineThemeManager.DEFAULT_DESKTOP_THEME)
  }
  m.createContainerAsync(d){c->if(c!=null){if(co.isActive)co.resume(c)}else if(co.isActive)co.cancel(IllegalStateException("Winlator could not create the NHH container."))}
 }

 fun openControlsEditor(a:MainActivity){val p=NhhControllerRuntime.ensureNhhProfile(a);a.startActivity(Intent(a,com.winlator.ControlsEditorActivity::class.java).apply{putExtra("profile_id",p.id);putExtra("nhh_editor_safe_area",true)})}
 fun resetControlsLayout(c:Context){NhhControllerRuntime.resetNhhProfile(c);appendLaunchLog(c,"Restored NHH Xbox layout.")}
 fun enterDesktop(a:MainActivity,p:SharedPreferences){
  val id=p.getInt(PREF_CONTAINER_ID,0);require(id>0){"The NHH container has not been created yet."}
  require(ContainerManager(a).getContainerById(id)!=null){"The NHH container is missing. Re-import the game ZIP."}
  appendLaunchLog(a,"Entering NHH Container Desktop Mode. Container="+id)
  a.startActivity(Intent(a,XServerDisplayActivity::class.java).apply{putExtra("container_id",id);putExtra("nhh_desktop_mode",true)})
 }

 fun loadSettings(c:Context,p:SharedPreferences):LauncherRuntimeSettings{
  val id=p.getInt(PREF_CONTAINER_ID,0);val x=if(id>0)ContainerManager(c).getContainerById(id)else null
  val enabled=p.getInt(PREF_PROFILE,NhhControllerRuntime.NHH_PROFILE_ID)!=0
  if(x==null)return LauncherRuntimeSettings(forceFullscreen=p.getBoolean(PREF_FULLSCREEN,true),onScreenControls=enabled,preferredInputApi=NhhControllerRuntime.getPreferredInputApi(p),overlayOpacity=NhhControllerRuntime.getOverlayOpacity(p),controlScale=NhhControllerRuntime.getControlScale(p),stickDeadzone=NhhControllerRuntime.getStickDeadzone(p))
  val d=GraphicsDrivers.parseIdentifiers(x.graphicsDriver);val esync=!x.envVars.split(Regex("\\s+")).any{it=="WINEESYNC=0"}
  return LauncherRuntimeSettings(x.screenSize,d[0],d[1],x.getDXWrapper(),x.getAudioDriver(),x.getBox64Preset(),esync,x.getHUDMode().toInt()!=0,p.getBoolean(PREF_FULLSCREEN,true),enabled,NhhControllerRuntime.getPreferredInputApi(p),NhhControllerRuntime.getOverlayOpacity(p),NhhControllerRuntime.getControlScale(p),NhhControllerRuntime.getStickDeadzone(p))
 }

 fun saveSettings(c:Context,p:SharedPreferences,s:LauncherRuntimeSettings){
  val id=p.getInt(PREF_CONTAINER_ID,0);require(id>0){"Import the game before changing runtime settings."}
  val x=ContainerManager(c).getContainerById(id)?:throw IllegalStateException("The NHH container is missing.")
  x.setScreenSize(s.resolution);x.setGraphicsDriver(s.vulkanDriver+","+s.openGlDriver);x.setDXWrapper(s.dxWrapper);x.setAudioDriver(s.audioDriver);x.setBox64Preset(s.box64Preset)
  x.setEnvVars(setEnv(x.envVars,"WINEESYNC",if(s.wineEsync)"1"else"0"));x.setHUDMode(if(s.showFpsHud)1 else 0);x.saveData()
  p.edit().putInt(PREF_PROFILE,if(s.onScreenControls)NhhControllerRuntime.NHH_PROFILE_ID else 0).putBoolean(PREF_FULLSCREEN,s.forceFullscreen).apply()
  NhhControllerRuntime.saveControllerPreferences(c,p,s.overlayOpacity,s.controlScale,s.stickDeadzone,s.preferredInputApi)
 }

 fun launchGame(c:Context,p:SharedPreferences){
  val r=RootFS.find(c);require(r.isValid()&&r.getVersion()>=rootVersion){"Winlator system files are not installed."}
  val id=p.getInt(PREF_CONTAINER_ID,0);require(id>0){"The NHH container has not been created."}
  require(ContainerManager(c).getContainerById(id)!=null){"The NHH container no longer exists."}
  val e=File(p.getString(PREF_GAME_PATH,null)?:throw IllegalStateException("The imported executable path is missing.")).canonicalFile
  require(e.isFile){"NHH.exe is missing from the imported game folder."}
  val profile=p.getInt(PREF_PROFILE,NhhControllerRuntime.NHH_PROFILE_ID);if(profile!=0)NhhControllerRuntime.ensureNhhProfile(c)
  c.startActivity(Intent(c,XServerDisplayActivity::class.java).apply{putExtra("container_id",id);putExtra("exec_path",e.absolutePath);putExtra("nhh_controls_profile_id",profile);putExtra("nhh_force_fullscreen",p.getBoolean(PREF_FULLSCREEN,true));if(c !is Activity)addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
 }

 fun readLaunchLog(c:Context):String{val f=File(c.filesDir,"nhh-launch.log");return if(f.isFile)f.readText()else"No launch report has been recorded yet."}
 fun shareLaunchReport(c:Context){
  val d=NhhControllerRuntime.snapshot
  val report=buildString{appendLine("=== NHH Controller Diagnostics ===");appendLine("State: "+d.state);appendLine("Profile: "+d.profileId);appendLine("Elements: "+d.elementCount);appendLine("VirtualGamepad: "+d.isVirtualGamepad);appendLine("Input API: "+d.preferredInputApi);appendLine("WinHandler: "+d.winHandlerReady);appendLine("Dispatches: "+d.dispatchCount);append(readLaunchLog(c))}
  c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"NHH Launcher diagnostic report");putExtra(Intent.EXTRA_TEXT,report)},"Share launch report"))
 }

 private fun setEnv(s:String,k:String,v:String)=s.split(Regex("\\s+")).filter{it.isNotBlank()&&!it.startsWith(k+"=")}.plus(k+"="+v).joinToString(" ")
 private fun appendLaunchLog(c:Context,msg:String){try{File(c.filesDir,"nhh-launch.log").appendText(System.currentTimeMillis().toString()+" "+msg+"\n")}catch(t:Throwable){Log.w("NHHLauncher","Could not write launch report",t)}}
}