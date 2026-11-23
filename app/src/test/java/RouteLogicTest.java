import static org.junit.Assert.*;

import android.media.AudioDeviceInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * Pure helpers of the routing helper (route/AppRoute.java, default package; compiled into the unit tests through
 * sourceSets["test"].java.srcDir("../route")): volume maths, control port auth/commands, version reporting,
 * Bluetooth whitelist, idle timeout and call mode helpers.
 */
public class RouteLogicTest {
    // ---- volume percentage conversion ----
    @Test public void percent_isRelativeToTheMaximum() {
        assertEquals(50, AppRoute.percent(75, 150));
        assertEquals(100, AppRoute.percent(150, 150));
        assertEquals(0, AppRoute.percent(0, 150));
        assertEquals(7, AppRoute.percent(1, 15));
        assertEquals(0, AppRoute.percent(5, 0));       // unknown maximum: never divide by zero
    }

    @Test public void idxForPercent_mapsAccuratelyAndClamps() {
        assertEquals(0, AppRoute.idxForPercent(0, 0, 150));
        assertEquals(75, AppRoute.idxForPercent(50, 0, 150));
        assertEquals(150, AppRoute.idxForPercent(100, 0, 150));
        assertEquals(0, AppRoute.idxForPercent(-20, 0, 150));
        assertEquals(150, AppRoute.idxForPercent(150, 0, 150));
        assertEquals(8, AppRoute.idxForPercent(50, 1, 15));
    }

    // ---- control port access: who ----
    @Test public void allowed_rootShellAndTheOwnerAppOnly() {
        assertTrue(AppRoute.allowed(0, -1));
        assertTrue(AppRoute.allowed(2000, -1));
        assertTrue(AppRoute.allowed(10123, 10123));
        assertFalse(AppRoute.allowed(10124, 10123));   // another app
        assertFalse(AppRoute.allowed(10123, -1));      // started without SPLITAUDIO_UID: shell and root only
        assertFalse(AppRoute.allowed(1000, -1));       // system uid is not special
        assertFalse(AppRoute.allowed(1000, 10123));
        assertFalse(AppRoute.allowed(-1, -1));         // a failed lookup never matches an unset owner
    }

    @Test public void uidProblem_decidesWhoIsLetIn() {
        assertNull(AppRoute.uidProblem(10234, 10234, true));                         // the app that started the router
        assertNull(AppRoute.uidProblem(2000, 10234, true));                          // shell
        assertNull(AppRoute.uidProblem(0, -1, true));                                // root
        assertNull(AppRoute.uidProblem(2000, -1, false));
        assertNotNull(AppRoute.uidProblem(10235, 10234, true));                      // another app
        assertTrue(AppRoute.uidProblem(10235, 10234, true).contains("10235"));
        assertNotNull(AppRoute.uidProblem(10235, 10234, false));                     // also where the lookup is only "best effort"
        assertNotNull(AppRoute.uidProblem(10234, -1, true));                         // started without an owner: shell and root only
        assertNotNull(AppRoute.uidProblem(AppRoute.UID_AMBIGUOUS, 10234, true));
        assertNotNull(AppRoute.uidProblem(AppRoute.UID_AMBIGUOUS, 10234, false));    // disagreeing table entries are never waved through
        assertNotNull(AppRoute.uidProblem(AppRoute.UID_UNKNOWN, 10234, true));       // the lookup works here, but this client is not in the table
        assertNull(AppRoute.uidProblem(AppRoute.UID_UNKNOWN, 10234, false));         // the lookup does not work on this device at all: the token decided
    }

    @Test public void parseUid_readsTheEnvironmentValue() {
        assertEquals(10123, AppRoute.parseUid("10123"));
        assertEquals(1010123, AppRoute.parseUid(" 1010123 "));
        assertEquals(-1, AppRoute.parseUid(null));
        assertEquals(-1, AppRoute.parseUid(""));
        assertEquals(-1, AppRoute.parseUid("abc"));
        assertEquals(-1, AppRoute.parseUid("10123; id"));
    }

    @Test public void controlCommands_unknownOrMalformedGetErr() {
        assertEquals("err", AppRoute.volCmd(""));
        assertEquals("err", AppRoute.volCmd("vol"));
        assertEquals("err", AppRoute.volCmd("vol up"));
        assertEquals("err", AppRoute.volCmd("vol set"));
        assertEquals("err", AppRoute.volCmd("vol set abc"));
        assertEquals("err", AppRoute.volCmd("vol set 101"));
        assertEquals("err", AppRoute.volCmd("vol set -1"));
        assertEquals("err", AppRoute.volCmd("vol sideways com.a"));   // only up / down / get / set
        assertEquals("err", AppRoute.volCmd("reboot"));
    }

    @Test public void controlCommands_stateModeAndVerWithoutARule() {
        AppRoute.paused = false;
        AppRoute.runningVer = "";
        assertEquals("off", AppRoute.volCmd("state"));    // no audio policy registered in a test
        assertEquals("legacy", AppRoute.volCmd("mode"));
        assertEquals("unknown", AppRoute.volCmd("ver"));
        AppRoute.runningVer = "deadbeef";
        assertEquals("deadbeef", AppRoute.volCmd("ver"));
        AppRoute.runningVer = "";
    }

    // ---- control port access: the token ----
    private static final String TOK = "0123456789abcdef0123456789abcdef";

    @Test public void validToken_acceptsTheAppsFormatAndNothingOdd() {
        assertEquals(TOK, AppRoute.validToken(TOK));
        assertEquals(TOK, AppRoute.validToken("  " + TOK + "\n"));                     // as read from a file
        assertNotNull(AppRoute.validToken("0123456789abcdef"));                       // 16 is the shortest
        assertEquals("Ab9Zz0Ab9Zz0Ab9Zz0", AppRoute.validToken("Ab9Zz0Ab9Zz0Ab9Zz0"));   // letters and digits are fine
        assertNull(AppRoute.validToken(null));
        assertNull(AppRoute.validToken(""));
        assertNull(AppRoute.validToken("abc"));
        assertNull(AppRoute.validToken("0123456789abcde"));                           // 15: too short
        assertNull(AppRoute.validToken(TOK + TOK + TOK + TOK + "0"));                 // 129: too long
        assertNull(AppRoute.validToken("0123456789abcdef0123456789abcde;"));          // not a letter or digit
        assertNull(AppRoute.validToken("0123456789abcdef 0123456789abcdef"));         // a blank inside
        assertNull(AppRoute.validToken("0123456789abcdef\n0123456789abcdef"));
    }

    @Test public void authToken_readsTheAuthLineStrictly() {
        assertEquals("abc123", AppRoute.authToken("auth abc123"));
        assertEquals("abc123", AppRoute.authToken("auth abc123\r"));          // a client that ends its lines with \r\n
        assertEquals(TOK, AppRoute.authToken("auth " + TOK));
        assertNull(AppRoute.authToken(null));                                 // end of stream / timeout / line too long
        assertNull(AppRoute.authToken(""));
        assertNull(AppRoute.authToken("auth"));
        assertNull(AppRoute.authToken("auth "));
        assertNull(AppRoute.authToken("authabc123"));
        assertNull(AppRoute.authToken("AUTH abc123"));
        assertNull(AppRoute.authToken("auth  abc123"));                       // two blanks
        assertNull(AppRoute.authToken("auth abc 123"));
        assertNull(AppRoute.authToken("auth\tabc123"));
        assertNull(AppRoute.authToken("state"));                              // a command where the auth line belongs
        assertNull(AppRoute.authToken("vol up com.a"));
    }

    @Test public void tokenMatches_exactCopyOnly() {
        assertTrue(AppRoute.tokenMatches(TOK, TOK));
        assertTrue(AppRoute.tokenMatches(new String(TOK), TOK));
        assertFalse(AppRoute.tokenMatches("0123456789abcdef0123456789abcdee", TOK));   // last character
        assertFalse(AppRoute.tokenMatches("1123456789abcdef0123456789abcdef", TOK));   // first character
        assertFalse(AppRoute.tokenMatches(TOK.substring(1), TOK));                       // shorter
        assertFalse(AppRoute.tokenMatches(TOK + "0", TOK));                              // longer
        assertFalse(AppRoute.tokenMatches(TOK.toUpperCase(), TOK));                      // case counts
        assertFalse(AppRoute.tokenMatches(null, TOK));
        assertFalse(AppRoute.tokenMatches(TOK, null));
        assertFalse(AppRoute.tokenMatches(null, null));
        assertFalse(AppRoute.tokenMatches("", ""));                                      // an empty token never matches
    }

    @Test public void readTokenFile_firstLineTrimmed() throws Exception {
        assertEquals(TOK, AppRoute.readTokenFile(temp(TOK + "\n")));
        assertEquals(TOK, AppRoute.readTokenFile(temp("  " + TOK + "  ")));
        assertEquals(TOK, AppRoute.readTokenFile(temp(TOK)));                 // approute.sh writes it without a newline
        assertNull(AppRoute.readTokenFile(temp("")));
        assertNull(AppRoute.readTokenFile(new File("/nonexistent/approute.token")));
        assertNull(AppRoute.validToken(AppRoute.readTokenFile(temp("\n"))));  // an empty line is not a token
    }

    // ---- control port access: the uid on the other end, from /proc/net/tcp and /proc/net/tcp6 ----
    // The router listens on 47821 = 0xBACD; the client's source port is 40000 = 0x9C40.
    private static final String HEAD4 = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";
    private static final String HEAD6 = "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";
    private static final String CLIENT4 = "   3: 0100007F:9C40 0100007F:BACD 01 00000000:00000000 00:00000000 00000000 10234        0 5551212 1 0000000000000000 20 4 30 10 -1";
    private static final String ROUTER4 = "   4: 0100007F:BACD 0100007F:9C40 01 00000000:00000000 00:00000000 00000000  2000        0 5551213 1 0000000000000000 20 4 30 10 -1";
    private static final String CLIENT6 = "   1: 0000000000000000FFFF00000100007F:9C40 0000000000000000FFFF00000100007F:BACD 01 00000000:00000000 00:00000000 00000000 10234        0 5551214 1 0000000000000000 20 4 30 10 -1";
    private static final String ROUTER6 = "   2: 0000000000000000FFFF00000100007F:BACD 0000000000000000FFFF00000100007F:9C40 01 00000000:00000000 00:00000000 00000000  2000        0 5551215 1 0000000000000000 20 4 30 10 -1";
    private static final String OTHER4 = "   9: 0100007F:9C41 0100007F:BACD 01 00000000:00000000 00:00000000 00000000 10300        0 5551299 1 0000000000000000 20 4 30 10 -1";

    private static java.util.List<String> lines(String... l) { return java.util.Arrays.asList(l); }

    @Test public void uidOfConnection_findsTheClientInTheIpv4Table() {
        assertEquals(10234, AppRoute.uidOfConnection(lines(HEAD4, CLIENT4, ROUTER4, OTHER4), 40000, 47821));
        assertEquals(10300, AppRoute.uidOfConnection(lines(HEAD4, CLIENT4, ROUTER4, OTHER4), 40001, 47821));
    }

    @Test public void uidOfConnection_findsTheClientInTheIpv6Table() {
        // Java sockets are IPv6 sockets: 127.0.0.1 shows up as ::ffff:127.0.0.1
        assertEquals(10234, AppRoute.uidOfConnection(lines(HEAD6, CLIENT6, ROUTER6), 40000, 47821));
    }

    @Test public void uidOfConnection_theRoutersOwnEndIsNotTheClient() {
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4, ROUTER4), 40000, 47821));   // would say uid 2000 = shell
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD6, ROUTER6), 40000, 47821));
    }

    @Test public void uidOfConnection_noMatch_isUnknown() {
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4, CLIENT4), 40002, 47821));    // other source port
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4, CLIENT4), 40000, 47822));    // other router port
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4), 40000, 47821));
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(), 40000, 47821));                  // unreadable /proc file
    }

    @Test public void uidOfConnection_timeWaitLinesAreIgnored() {
        // a closed connection lingering in TIME_WAIT (state 06) always reports uid 0 = root: it must never count as the client
        String tw = CLIENT4.replace(" 01 ", " 06 ").replace(" 10234 ", "     0 ");
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4, tw), 40000, 47821));
    }

    @Test public void uidOfConnection_onlyLoopbackConnectionsCount() {
        String lan = CLIENT4.replace("0100007F:9C40", "0A01A8C0:9C40");                                // 192.168.1.10 -> 127.0.0.1:47821 cannot happen
        String out = CLIENT4.replace("0100007F:BACD", "0A01A8C0:BACD");                               // our port number on another host
        String lan6 = CLIENT6.replace("0000000000000000FFFF00000100007F:9C40", "0000000000000000FFFF00000A01A8C0:9C40");
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4, lan), 40000, 47821));
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD4, out), 40000, 47821));
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines(HEAD6, lan6), 40000, 47821));
        String lo6 = CLIENT6.replace("0000000000000000FFFF00000100007F", "00000000000000000000000001000000");   // ::1
        assertEquals(10234, AppRoute.uidOfConnection(lines(HEAD6, lo6), 40000, 47821));
    }

    @Test public void uidOfConnection_entriesThatDisagreeAreAmbiguous() {
        String twin = CLIENT4.replace("0100007F:9C40", "0200007F:9C40").replace(" 10234 ", " 10300 ");    // same ports from 127.0.0.2, another uid
        assertEquals(AppRoute.UID_AMBIGUOUS, AppRoute.uidOfConnection(lines(HEAD4, CLIENT4, twin), 40000, 47821));
        assertEquals(10234, AppRoute.uidOfConnection(lines(HEAD4, CLIENT4, CLIENT4), 40000, 47821));      // the same uid twice is fine
        assertFalse(AppRoute.allowed(AppRoute.UID_AMBIGUOUS, 10234));
        assertFalse(AppRoute.allowed(AppRoute.UID_UNKNOWN, -1));
    }

    @Test public void uidOfConnection_junkLinesAreSkipped() {
        String badUid = CLIENT4.replace(" 10234 ", " abc ");
        String hugeUid = CLIENT4.replace(" 10234 ", " 4294967295 ");                                     // unmapped uid
        String shortLine = "   3: 0100007F:9C40 0100007F:BACD 01";
        assertEquals(AppRoute.UID_UNKNOWN, AppRoute.uidOfConnection(lines("", "garbage", shortLine, badUid, hugeUid), 40000, 47821));
        assertEquals(10234, AppRoute.uidOfConnection(lines("", "garbage", shortLine, badUid, CLIENT4), 40000, 47821));
    }

    @Test public void readLines_keepsOnlyTheLinesThatMentionThePort() throws Exception {
        File f = temp(HEAD4 + "\n" + CLIENT4 + "\n" + ROUTER4 + "\n" + OTHER4 + "\n" + CLIENT4.toLowerCase() + "\n");
        java.util.List<String> l = AppRoute.readLines(f.getPath(), 40000);       // 0x9C40: the client's end and the router's end of that connection
        assertEquals(3, l.size());                                                  // (the kernel writes capitals; lower case is accepted too)
        assertEquals(1, AppRoute.readLines(f.getPath(), 40001).size());
        assertEquals(0, AppRoute.readLines(f.getPath(), 1234).size());
        assertEquals(10234, AppRoute.uidOfConnection(l, 40000, 47821));
        assertEquals(0, AppRoute.readLines("/nonexistent/net/tcp", 40000).size());   // unreadable file: nothing found, no exception
    }

    @Test public void loopback_recognisesTheAddressFormsOfProcNetTcp() {
        assertTrue(AppRoute.loopback("0100007F:1234"));                                // 127.0.0.1
        assertTrue(AppRoute.loopback("0200007F:1234"));                                // 127.0.0.2
        assertTrue(AppRoute.loopback("0000000000000000FFFF00000100007F:1234"));        // ::ffff:127.0.0.1
        assertTrue(AppRoute.loopback("00000000000000000000000001000000:1234"));        // ::1
        assertTrue(AppRoute.loopback("0100007f:1234"));                                // lower case is fine
        assertFalse(AppRoute.loopback("0A01A8C0:1234"));                               // 192.168.1.10
        assertFalse(AppRoute.loopback("00000000:1234"));                               // 0.0.0.0
        assertFalse(AppRoute.loopback("0000000000000000FFFF00000A01A8C0:1234"));       // ::ffff:192.168.1.10
        assertFalse(AppRoute.loopback("00000000000000000000000000000000:1234"));       // ::
        assertFalse(AppRoute.loopback("0100007F"));                                    // no port
        assertFalse(AppRoute.loopback("abcd:1234"));
        assertFalse(AppRoute.loopback(""));
    }

    @Test public void hexPort_readsTheHexPortAfterTheColon() {
        assertEquals(47821, AppRoute.hexPort("0100007F:BACD"));
        assertEquals(40000, AppRoute.hexPort("0000000000000000FFFF00000100007F:9C40"));
        assertEquals(0, AppRoute.hexPort("00000000:0000"));
        assertEquals(-1, AppRoute.hexPort("0100007F"));
        assertEquals(-1, AppRoute.hexPort("0100007F:ZZZZ"));
    }

    @Test public void readLineLimited_stopsAtNewlineAndAtTheLimit() throws Exception {
        java.io.InputStream two = new java.io.ByteArrayInputStream("auth abc\nstate\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("auth abc", AppRoute.readLineLimited(two, 512));
        assertEquals("state", AppRoute.readLineLimited(two, 512));                     // nothing after the first line was swallowed
        assertNull(AppRoute.readLineLimited(two, 512));                                // end of stream
        assertEquals("abc\r", AppRoute.readLineLimited(new java.io.ByteArrayInputStream("abc\r\n".getBytes(StandardCharsets.UTF_8)), 512));   // trimmed by the caller
        assertEquals("12345", AppRoute.readLineLimited(new java.io.ByteArrayInputStream("12345\n".getBytes(StandardCharsets.UTF_8)), 5));      // exactly the limit
        assertNull(AppRoute.readLineLimited(new java.io.ByteArrayInputStream("123456\n".getBytes(StandardCharsets.UTF_8)), 5));                // one more
        assertNull(AppRoute.readLineLimited(new java.io.ByteArrayInputStream("no newline".getBytes(StandardCharsets.UTF_8)), 512));           // cut off
    }

    // ---- call mode distinction: cellular phone calls vs WeChat VoIP ----
    @Test public void callModes_distinguishPhoneCallsFromVoip() {
        assertFalse(AppRoute.isPhoneCallMode(0));   // NORMAL
        assertTrue(AppRoute.isPhoneCallMode(1));    // RINGTONE
        assertTrue(AppRoute.isPhoneCallMode(2));    // IN_CALL
        assertFalse(AppRoute.isPhoneCallMode(3));   // IN_COMMUNICATION (WeChat voice/video)
        assertTrue(AppRoute.isVoipMode(3));
        assertFalse(AppRoute.isVoipMode(0));
        assertFalse(AppRoute.isVoipMode(2));
    }

    // ---- standby sleep pause / resume control commands ----
    @Test public void controlCommands_pauseAndResumeToggleStandbyState() {
        AppRoute.paused = false;
        assertEquals("off", AppRoute.volCmd("state"));
        assertEquals("ok", AppRoute.volCmd("pause"));
        assertEquals("paused", AppRoute.volCmd("state"));
        assertEquals("ok", AppRoute.volCmd("resume"));
        assertEquals("off", AppRoute.volCmd("state"));
        assertEquals("err", AppRoute.volCmd("setidle -1"));
        assertEquals("err", AppRoute.volCmd("setidle 2000"));
        assertEquals("err", AppRoute.volCmd("setbt !!!not_base64!!!"));
    }

    // ---- files written by the app (through approute.sh) ----
    private static File temp(String text) throws Exception {
        File f = File.createTempFile("approute", ".txt");
        f.deleteOnExit();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test public void readFirstLine_trimsAndHandlesMissing() throws Exception {
        assertEquals("abc123", AppRoute.readFirstLine(temp("  abc123 \nsecond\n")));
        assertEquals("", AppRoute.readFirstLine(temp("")));
        assertEquals("", AppRoute.readFirstLine(new File("/nonexistent/approute.ver")));
    }

    @Test public void btFilter_matchesSpeakerAndRejectsHeadphones() throws Exception {
        java.util.List<AppRoute.BtEntry> filter = AppRoute.readBtFilter(temp("aa:bb:cc:dd:ee:ff|Living Room Speaker\n|Bedroom Speaker\n\n"));
        assertEquals(2, filter.size());
        assertTrue(AppRoute.matchesBtFilter(filter, "AA:BB:CC:DD:EE:FF", "Renamed Speaker"));  // MAC match wins even if renamed
        assertFalse(AppRoute.matchesBtFilter(filter, "11:22:33:44:55:66", "Xiaomi Buds 5"));   // headphones rejected
        assertTrue(AppRoute.matchesBtFilter(filter, "02:00:00:00:00:00", "Bedroom Speaker"));  // placeholder MAC falls back to name
        assertTrue(AppRoute.matchesBtFilter(java.util.Collections.emptyList(), "11:22:33:44:55:66", "Anything")); // empty = allow all
    }

    @Test public void idleTimeout_supportsDisableAndCustomMinutes() throws Exception {
        assertEquals(AppRoute.DEFAULT_IDLE_MINUTES, AppRoute.readIdleMinutes(new File("/nonexistent/approute.idle")));
        assertEquals(0, AppRoute.readIdleMinutes(temp("0\n")));
        assertEquals(15, AppRoute.readIdleMinutes(temp(" 15 \n")));
        assertEquals(0, AppRoute.readIdleMinutes(temp("-5\n")));
        assertEquals(1440, AppRoute.readIdleMinutes(temp("9999\n")));
        assertFalse(AppRoute.shouldExitOnIdle(0, 10_000_000L, 0L));           // 0 = disabled
        assertFalse(AppRoute.shouldExitOnIdle(2, 119_999L, 0L));
        assertTrue(AppRoute.shouldExitOnIdle(2, 120_000L, 0L));
    }

    @Test public void isBtOutputType_recognisesA2dpAndBle() {
        assertTrue(AppRoute.isBtOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertTrue(AppRoute.isBtOutputType(26));
        assertTrue(AppRoute.isBtOutputType(27));
        assertFalse(AppRoute.isBtOutputType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertFalse(AppRoute.isBtOutputType(AudioDeviceInfo.TYPE_WIRED_HEADSET));
    }
}
