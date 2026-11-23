#!/system/bin/sh
# Start/stop AppRoute on the phone (runs as the shell user via Shizuku or built-in wireless ADB).
# Usage: sh /data/local/tmp/approute.sh start|stop|status|ask <command>
# The app puts its own uid and control token in the environment (SPLITAUDIO_UID=<uid> SPLITAUDIO_TOKEN=<token> sh approute.sh ...):
#   the router inherits both and serves 127.0.0.1:47821 only for "auth <token>" from that uid, shell or root.
D=/data/local/tmp; LOG=$D/approute.log
umask 022   # files made here (approute.log, config files) are 0644, not world-writable; the token file sets 077
# Default music apps (QQ Music + common domestic & global players; only installed ones count).
DEF_MUSIC="com.tencent.qqmusic com.netease.cloudmusic com.kugou.android cn.kuwo.player com.luna.music com.google.android.apps.youtube.music app.morphe.android.apps.youtube.music com.spotify.music com.apple.android.music deezer.android.app com.aspiro.tidal com.amazon.mp3 com.soundcloud.android com.pandora.android com.maxmpz.audioplayer"
pid(){ for p in $(pgrep -f "app_process /system/bin AppRoute"); do grep -q mixer /proc/$p/cmdline 2>/dev/null || { echo $p; break; }; done; }
trimlog(){ S=$(wc -c < $LOG 2>/dev/null); [ "${S:-0}" -gt 1048576 ] && { tail -n 300 $LOG > $LOG.tmp && cat $LOG.tmp > $LOG; rm -f $LOG.tmp; }; return 0; }
settoken(){
  T="$SPLITAUDIO_TOKEN"
  case "$T" in *[!0-9a-f]*) T="" ;; esac
  [ "${#T}" = 32 ] || T=$(head -c 16 /dev/urandom 2>/dev/null | od -An -tx1 | tr -d ' \n')
  [ "${#T}" = 32 ] || T=$(tr -d '\n-' < /proc/sys/kernel/random/uuid 2>/dev/null)
  ( umask 077; printf '%s' "$T" > $D/approute.token )
  chmod 600 $D/approute.token
  SPLITAUDIO_TOKEN="$T"; export SPLITAUDIO_TOKEN
}
case "$1" in
  start)
    trimlog
    MP=$(pgrep -f "[A]ppRoute mixer" 2>/dev/null | head -1); [ -n "$MP" ] && kill $MP 2>/dev/null
    [ -n "$(pid)" ] && { echo "already pid=$(pid)"; exit 0; }
    uid(){ pm list packages -U "$1" | grep "package:$1 " | sed 's/.*uid://'; }
    MUSIC=$(grep -E '^[A-Za-z0-9_.]+$' $D/approute.music 2>/dev/null | tr '\n' ' ')
    [ -z "$(echo $MUSIC)" ] && MUSIC="$DEF_MUSIC"
    M=""; for p in $MUSIC; do u=$(uid "$p"); [ -n "$u" ] && M="$M${M:+,}$u"; done
    # Every app uid except the music apps (including work-profile / second-space uids); system uids (<10000) untouched.
    # User-installed apps (-3) first, so they are always inside the routing limit.
    U=$( { pm list packages -3 -U; pm list packages -U; } | sed 's/.*uid://' | tr ',' '\n' | awk -v m="$M" 'BEGIN{n=split(m,a,",");for(i=1;i<=n;i++)x[a[i]]=1} ($1%100000)>=10000 && !($1 in x) && !($1 in seen){seen[$1]=1;print}' | tr '\n' ' ')
    settoken
    CLASSPATH=$D/approute.dex setsid nohup app_process /system/bin AppRoute "$M" $U >> $LOG 2>&1 < /dev/null &
    sleep 2; echo "started pid=$(pid)"; tail -2 $LOG ;;
  stop) P=$(pid); [ -n "$P" ] && kill $P; echo "stopped" ;;
  status) P=$(pid); echo "pid=${P:-none}"; tail -3 $LOG ;;
  ask) shift; CLASSPATH=$D/approute.dex app_process /system/bin AppRoute ask "$*" ;;
esac
