package net.dalerank.caesaria;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Telemetry (spec section 7), v2.
 *
 * Design goals:
 *  - reports must survive process death (native exit(-1)/SIGSEGV kill the
 *    process instantly, so nothing can be uploaded from the dying process);
 *  - therefore: every crash/abnormal-exit is persisted to disk first, and the
 *    NEXT application start uploads all pending reports;
 *  - the engine (CaesarIA activity) marks its session start in
 *    telemetry/engine_session.txt; if that file still exists on the next
 *    launch, the previous engine session ended abnormally (native crash,
 *    exit(), process kill) and a report is generated automatically.
 *
 * Credentials come from (in priority order):
 *   1. environment CAESARIA_TELEMETRY_TOKEN / CAESARIA_TELEMETRY_REPO (CI/dev)
 *   2. assets/telemetry.properties (written by GitHub Actions from repo secrets;
 *      the token is never committed to the public repository)
 *
 * When no credentials are available the reports stay on the device.
 */
public class CrashReporter
{
  private static final String TAG = "CAESARIA_TELEMETRY";
  private static final String DIR = "telemetry";
  private static final String SESSION_FLAG = "engine_session.txt";
  private static final String ENGINE_ERROR = "engine_error.txt";
  private static final int MAX_PENDING = 6;
  private static final int MAX_BODY = 64000;

  private static boolean sInitDone = false;
  private static volatile boolean sEngineRunning = false;
  private static Context sAppContext = null;
  private static String sToken = "";
  private static String sRepo = "";
  private static String sApkVersion = "";
  private static String sCommit = "";
  private static Thread.UncaughtExceptionHandler sPrevHandler;

  /** Call from every activity onCreate; safe and cheap when repeated. */
  public static void init( Context ctx )
  {
    if( sInitDone ) return;
    try
    {
      sAppContext = ctx.getApplicationContext();
      ensureDir( ctx );

      loadConfig( ctx );
      sApkVersion = appVersion( ctx );
      sCommit = readCommit( ctx );

      sPrevHandler = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler( new Thread.UncaughtExceptionHandler()
      {
        @Override
        public void uncaughtException( Thread t, Throwable e )
        {
          // save only - the process is dying, uploading from here is
          // unreliable; the next application start will upload the file
          saveCrash( t, e );
          if( sPrevHandler != null )
            sPrevHandler.uncaughtException( t, e );
        }
      } );

      sInitDone = true;

      // collect everything that survived from previous sessions, then try to
      // deliver pending reports in the background
      collectPreviousSession( ctx );

      new Thread( new Runnable()
      {
        @Override
        public void run() { uploadPending(); }
      }, "caesaria-telemetry" ).start();

      Log.i( TAG, "initialized (repo=" + (sRepo.isEmpty() ? "none" : sRepo) + ")" );
    }
    catch( Throwable th )
    {
      Log.w( TAG, "init failed", th );
    }
  }

  /**
   * Re-check for abnormal-exit markers and re-try pending uploads. Called
   * when the launcher resumes (e.g. right after the engine finished, so a
   * native fatal error recorded during the session is delivered at once).
   */
  public static void refresh( final Context ctx )
  {
    new Thread( new Runnable()
    {
      @Override
      public void run()
      {
        collectPreviousSession( ctx );
        uploadPending();
      }
    }, "caesaria-telemetry" ).start();
  }

  /** Engine session started (call from CaesarIA.onCreate). */
  public static void markEngineStart( Context ctx, String action, String loadfile )
  {
    try
    {
      File f = new File( logRoot( ctx ), SESSION_FLAG );
      SimpleDateFormat df = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss", Locale.US );
      String content = "start=" + df.format( new Date() ) + "\n"
          + "action=" + (action == null ? "(none)" : action) + "\n"
          + "loadfile=" + (loadfile == null ? "" : loadfile) + "\n"
          + "version=" + sApkVersion + "\n"
          + "commit=" + sCommit + "\n";
      java.io.FileOutputStream fo = new java.io.FileOutputStream( f );
      fo.write( content.getBytes( StandardCharsets.UTF_8 ) );
      fo.close();
      sEngineRunning = true;
      Log.i( TAG, "engine session marked" );
    }
    catch( Throwable t ) { Log.w( TAG, "markEngineStart failed", t ); }
  }

  /** Engine session finished cleanly (call from CaesarIA.onDestroy). */
  public static void markEngineEnd( Context ctx )
  {
    try
    {
      new File( logRoot( ctx ), SESSION_FLAG ).delete();
      sEngineRunning = false;
      Log.i( TAG, "engine session ended normally" );
    }
    catch( Throwable t ) { Log.w( TAG, "markEngineEnd failed", t ); }
  }

  // ------------------------------------------------------------------ collect

  /**
   * If the engine-session flag is still present the previous engine run died
   * abnormally (native crash / exit() / process kill). Build a report with the
   * engine's own log (stdout.txt), the session info and the full logcat ring
   * buffer (main + crash buffers contain the native tombstone backtrace).
   */
  private static void collectPreviousSession( Context ctx )
  {
    // if the engine activity is alive in this process (paused under the
    // launcher) its session flag is NOT an abnormal-exit marker
    boolean flagAlive = !sEngineRunning;
    File flag = new File( logRoot( ctx ), SESSION_FLAG );
    File errFile = new File( logRoot( ctx ), ENGINE_ERROR );
    boolean abnormal = (flag.exists() && flagAlive) || errFile.exists();
    try
    {
      if( !abnormal )
      {
        Log.i( TAG, "no pending engine failure markers" );
        return;
      }

      // crash buffer FIRST: it holds the native tombstone backtrace and is
      // small; the main buffer is dominated by engine log spam and must be
      // filtered, otherwise it eats the whole report body
      String logcatCrash = dumpLogcat( "-d -b crash -t 800 -v time" );
      String logcatMain = filterSpam( dumpLogcat( "-d -t 1500 -v time" ) );

      String session = flag.exists() ? readFile( flag ) : "(no session flag)";
      String engineErrors = errFile.exists() ? readFile( errFile ) : "(none)";
      String nativeLog = tailFile( new File( ctx.getExternalFilesDir( null ), "stdout.txt" ), 30000 );

      StringBuilder sb = new StringBuilder();
      sb.append( "CaesarIA abnormal engine exit report\n" )
        .append( "type: native/abnormal\n" )
        .append( "version: " ).append( sApkVersion ).append( '\n' )
        .append( "commit: " ).append( sCommit ).append( '\n' )
        .append( "device: " ).append( Build.MANUFACTURER ).append( ' ' ).append( Build.MODEL )
        .append( " (" ).append( Build.BOARD ).append(")\n" )
        .append( "android: " ).append( Build.VERSION.RELEASE )
        .append( " (api " ).append( Build.VERSION.SDK_INT ).append( ")\n" )
        .append( "---- engine session ----\n" ).append( session ).append( '\n' )
        .append( "---- engine_error.txt ----\n" ).append( engineErrors ).append( '\n' )
        .append( "---- logcat CRASH BUFFER (tombstone/backtrace) ----\n" ).append( logcatCrash ).append( '\n' );
      if( !nativeLog.isEmpty() )
        sb.append( "---- engine stdout.txt (tail) ----\n" ).append( nativeLog ).append( '\n' );
      else
        sb.append( "---- engine stdout.txt ----\n(native log not found)\n" );
      sb.append( "---- logcat main (spam filtered) ----\n" ).append( logcatMain );

      String ts = new SimpleDateFormat( "yyyyMMdd-HHmmss", Locale.US ).format( new Date() );
      writeFile( new File( logRoot( ctx ), "report-native-" + ts + ".txt" ), sb.toString() );
      Log.e( TAG, "engine failure markers found - report saved" );
    }
    catch( Throwable t )
    {
      Log.w( TAG, "collectPreviousSession failed", t );
    }
    finally
    {
      // markers are consumed: they now live inside the pending report file
      if( abnormal )
      {
        if( flagAlive ) flag.delete();
        errFile.delete();
      }
    }
  }

  // engine resource-loading warnings repeat thousands of times per second;
  // they flood the logcat ring buffer and eat the report body, hiding the
  // actual crash trace. Drop the known spam and collapse identical repeats.
  private static String filterSpam( String logcat )
  {
    if( logcat == null || logcat.isEmpty() ) return logcat;
    String[] spamPatterns = {
        "MetaDataHolder::Unknown objects",
        "TileHelper unknown image",
        "PictureBank: Unknown resource"
    };
    StringBuilder out = new StringBuilder();
    String prev = null; int repeats = 0;
    for( String line : logcat.split( "\n" ) )
    {
      boolean spam = false;
      for( String p : spamPatterns ) if( line.contains( p ) ) { spam = true; break; }
      if( spam ) continue;
      if( line.equals( prev ) ) { repeats++; continue; }
      if( repeats > 0 ) { out.append( "... (previous line repeated ").append( repeats ).append( " more times)\n" ); repeats = 0; }
      out.append( line ).append( '\n' );
      prev = line;
    }
    if( repeats > 0 ) out.append( "... (previous line repeated ").append( repeats ).append( " more times)\n" );
    return out.toString();
  }

  private static String dumpLogcat( String args )
  {
    try
    {
      Process p = Runtime.getRuntime().exec(
          ("/system/bin/logcat " + args).split( " " ) );
      InputStream in = p.getInputStream();
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[65536];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();
      p.waitFor();
      return bo.toString( "UTF-8" );
    }
    catch( Throwable t )
    {
      return "(logcat dump failed: " + t + ")";
    }
  }

  // ------------------------------------------------------------------ crashes

  private static void saveCrash( Thread t, Throwable e )
  {
    try
    {
      Context ctx = sAppContext;
      if( ctx == null ) return;
      String ts = new SimpleDateFormat( "yyyyMMdd-HHmmss", Locale.US ).format( new Date() );
      File f = new File( logRoot( ctx ), "crash-java-" + ts + ".txt" );
      StringBuilder sb = new StringBuilder();
      sb.append( "CaesarIA java crash report\n" )
        .append( "version: " ).append( sApkVersion ).append( '\n' )
        .append( "commit: " ).append( sCommit ).append( '\n' )
        .append( "device: " ).append( Build.MANUFACTURER ).append( ' ' ).append( Build.MODEL ).append( '\n' )
        .append( "android: " ).append( Build.VERSION.RELEASE ).append( " (api " ).append( Build.VERSION.SDK_INT ).append( ")\n" )
        .append( "thread: " ).append( t != null ? t.getName() : "?" ).append( "\n\n" )
        .append( Log.getStackTraceString( e ) ).append( '\n' )
        .append( "---- logcat main+crash ----\n" )
        .append( dumpLogcat( "-d -t 2000 -v time" ) );

      writeFile( f, sb.toString() );
      Log.e( TAG, "crash saved: " + f.getAbsolutePath() );
    }
    catch( Throwable th ) { Log.w( TAG, "saveCrash failed", th ); }
  }

  // ------------------------------------------------------------------- upload

  private static void uploadPending()
  {
    try
    {
      if( sToken.isEmpty() || sRepo.isEmpty() )
      {
        Log.w( TAG, "no telemetry credentials: reports kept on device only" );
        return;
      }

      Context ctx = sAppContext;
      if( ctx == null ) return;

      File[] files = logRoot( ctx ).listFiles();
      if( files == null ) return;

      List<File> pending = new ArrayList<File>();
      for( File f : files )
        if( f.getName().startsWith( "crash-" ) || f.getName().startsWith( "report-" ) )
          pending.add( f );

      // oldest first, cap the backlog so a broken device never spams the queue
      Collections.sort( pending, new Comparator<File>()
      {
        @Override public int compare( File a, File b ) { return a.getName().compareTo( b.getName() ); }
      } );
      while( pending.size() > MAX_PENDING )
      {
        pending.remove( 0 ).delete();
      }

      for( File f : pending )
      {
        String body = readFile( f );
        if( body.length() > MAX_BODY ) body = body.substring( 0, MAX_BODY ) + "\n... (truncated)";
        String kind = f.getName().startsWith( "crash-" ) ? "java crash" : "abnormal exit";
        String title = "[auto] " + kind + " on " + Build.MODEL + " v" + sApkVersion;
        boolean ok = postIssue( title, body );
        Log.i( TAG, "upload " + f.getName() + ": " + (ok ? "ok" : "failed (will retry next start)") );
        if( ok ) f.delete();
      }
    }
    catch( Throwable t )
    {
      Log.w( TAG, "uploadPending failed", t );
    }
  }

  private static boolean postIssue( String title, String body )
  {
    try
    {
      URL url = new URL( "https://api.github.com/repos/" + sRepo + "/issues" );
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod( "POST" );
      conn.setDoOutput( true );
      conn.setConnectTimeout( 15000 );
      conn.setReadTimeout( 30000 );
      conn.setRequestProperty( "Authorization", "token " + sToken );
      conn.setRequestProperty( "Accept", "application/vnd.github+json" );
      conn.setRequestProperty( "Content-Type", "application/json" );

      String json = "{\"title\":\"" + jesc( title ) + "\",\"body\":\"" + jesc( body ) + "\",\"labels\":[\"telemetry\"]}";
      OutputStream os = conn.getOutputStream();
      os.write( json.getBytes( StandardCharsets.UTF_8 ) );
      os.close();

      int code = conn.getResponseCode();
      InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
      if( in != null ) in.close();
      conn.disconnect();
      return code / 100 == 2;
    }
    catch( Throwable t )
    {
      Log.w( TAG, "postIssue failed", t );
      return false;
    }
  }

  // ------------------------------------------------------------------ helpers

  private static void ensureDir( Context ctx )
  {
    File dir = logRoot( ctx );
    if( !dir.exists() ) dir.mkdirs();
  }

  private static File logRoot( Context ctx )
  {
    return new File( ctx.getExternalFilesDir( null ), DIR );
  }

  private static void loadConfig( Context ctx )
  {
    sToken = envOrEmpty( "CAESARIA_TELEMETRY_TOKEN" );
    sRepo = envOrEmpty( "CAESARIA_TELEMETRY_REPO" );

    if( sToken.isEmpty() )
    {
      try
      {
        InputStream in = ctx.getAssets().open( "telemetry.properties" );
        java.util.Properties p = new java.util.Properties();
        p.load( in );
        in.close();
        sToken = p.getProperty( "telemetry.token", "" ).trim();
        sRepo = p.getProperty( "telemetry.repo", "" ).trim();
      }
      catch( Throwable ignored ) {}
    }
  }

  private static String envOrEmpty( String name )
  {
    try { String v = System.getenv( name ); return v == null ? "" : v.trim(); }
    catch( Throwable t ) { return ""; }
  }

  private static String appVersion( Context ctx )
  {
    try
    {
      return ctx.getPackageManager().getPackageInfo( ctx.getPackageName(), 0 ).versionName;
    }
    catch( PackageManager.NameNotFoundException e ) { return "?"; }
  }

  private static String readCommit( Context ctx )
  {
    try
    {
      InputStream in = ctx.getAssets().open( "build-commit.txt" );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();
      return bo.toString( "UTF-8" ).trim();
    }
    catch( Throwable t ) { return "unknown"; }
  }

  private static void writeFile( File f, String content )
  {
    try
    {
      java.io.FileOutputStream fo = new java.io.FileOutputStream( f );
      fo.write( content.getBytes( StandardCharsets.UTF_8 ) );
      fo.close();
    }
    catch( Throwable t ) { Log.w( TAG, "writeFile failed", t ); }
  }

  private static String readFile( File f )
  {
    try
    {
      FileInputStream in = new FileInputStream( f );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[65536];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();
      return bo.toString( "UTF-8" );
    }
    catch( Throwable t ) { return ""; }
  }

  private static String tailFile( File f, int maxBytes )
  {
    try
    {
      if( !f.exists() ) return "";
      long len = f.length();
      FileInputStream in = new FileInputStream( f );
      long skip = Math.max( 0, len - maxBytes );
      in.skip( skip );
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[65536];
      int r;
      while( (r = in.read( b )) > 0 ) bo.write( b, 0, r );
      in.close();
      return bo.toString( "UTF-8" );
    }
    catch( Throwable t ) { return ""; }
  }

  private static String jesc( String s )
  {
    return s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" )
            .replace( "\r", "\\r" ).replace( "\n", "\\n" )
            .replace( "\t", "\\t" );
  }
}
