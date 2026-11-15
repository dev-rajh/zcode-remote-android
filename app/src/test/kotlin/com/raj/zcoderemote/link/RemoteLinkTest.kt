package com.raj.zcoderemote.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkTest {

  @Test
  fun parsesDesktopQrUrl() {
    val link = RemoteLink.parse(
      "https://zcode.z.ai/remote/v4?sid=abc123SID&hash=deadbeefHASH&t=1731320000000&mid=MID1&name=MyMac&app_version=1.2.3",
    )
    assertTrue(link != null)
    assertTrue(link!!.url.startsWith("https://zcode.z.ai/remote/v4?"))
    assertTrue(link.url.contains("sid=abc123SID"))
    assertTrue(link.url.contains("hash=deadbeefHASH"))
    assertTrue(link.url.contains("t=1731320000000"))
    assertTrue(link.url.contains("mid=MID1"))
    assertTrue(link.url.contains("name=MyMac"))
    assertTrue(link.url.contains("app_version=1.2.3"))
    assertFalse(link.isLegacyTokenFlow)
  }

  @Test
  fun parsesQrUrlInAnyParamOrder() {
    val link = RemoteLink.parse(
      "https://zcode.z.ai/remote/v4?t=123456789&hash=H&sid=S",
    )
    assertTrue(link != null)
    assertTrue(link!!.url.contains("sid=S"))
    assertTrue(link.url.contains("hash=H"))
    assertTrue(link.url.contains("t=123456789"))
  }

  @Test
  fun parsesBareSidQueryString() {
    val link = RemoteLink.parse("sid=S1&hash=H1&t=999&theme=dark")
    assertTrue(link != null)
    assertEquals("https://zcode.z.ai/remote/v4?sid=S1&hash=H1&t=999&theme=dark", link!!.url)
  }

  @Test
  fun parsesCurrentDesktopQrWithoutHashAndTimestamp() {
    // The desktop's buildWebRemoteControlExternalQrUrl strips `hash` and `t`
    // so credentials never enter the scannable image — sid-only is the
    // current format.
    val link = RemoteLink.parse(
      "https://zcode.z.ai/remote/v4?sid=abc123SID&mid=MID456&name=Work%20Mac&app_version=3.16.2",
    )
    assertTrue(link != null)
    assertTrue(link!!.url.startsWith("https://zcode.z.ai/remote/v4?"))
    assertTrue(link.url.contains("sid=abc123SID"))
    assertTrue(link.url.contains("mid=MID456"))
    assertTrue(link.url.contains("name=Work"))
    assertFalse(link.hasEmbeddedCredentials)
    assertFalse(link.isLegacyTokenFlow)
  }

  @Test
  fun embeddedCredentialsDetectedOnLegacyHashLinks() {
    val legacy = RemoteLink.parse("https://zcode.z.ai/remote/v4?sid=S1&hash=H1&t=100")
    assertTrue(legacy?.hasEmbeddedCredentials == true)
    val bare = RemoteLink.parse("sid=S2")
    assertTrue(bare?.hasEmbeddedCredentials == false)
  }

  @Test
  fun parsesBareSidTokenOnly() {
    val link = RemoteLink.parse("sid=JUSTSID")
    assertTrue(link != null)
    assertEquals("https://zcode.z.ai/remote/v4?sid=JUSTSID", link!!.url)
  }

  @Test
  fun rejectsSidFlowMissingRequiredParams() {
    assertNull(RemoteLink.parse("https://zcode.z.ai/remote/v4?hash=H1&t=100")) // no sid
    assertNull(RemoteLink.parse("https://zcode.z.ai/remote/v4"))
    assertNull(RemoteLink.parse("hash=H1&t=1")) // no sid, bare query
  }

  @Test
  fun parsesLegacyTokenLinkAndMovesToWebRemotePath() {
    val link = RemoteLink.parse(
      "https://zcode.z.ai/web-remote?remoteControlToken=abc123TOKEN&relayOrigin=https%3A%2F%2Frelay.example.com",
    )
    assertTrue(link != null)
    assertTrue(link!!.url.startsWith("https://zcode.z.ai/web-remote?"))
    assertTrue(link.isLegacyTokenFlow)

    // Token on the /remote/v4 path gets re-routed to /web-remote (the SPA
    // only honors the legacy flow there).
    val misrouted = RemoteLink.parse("https://zcode.z.ai/remote/v4?remoteControlToken=abc123TOKEN")
    assertTrue(misrouted != null)
    assertTrue(misrouted!!.url.startsWith("https://zcode.z.ai/web-remote?"))
    assertTrue(misrouted.url.contains("relayOrigin=https%3A%2F%2Frelay.example.com").not())
  }

  @Test
  fun parsesUrlEncodedRelayOriginInBareQuery() {
    val link = RemoteLink.parse("remoteControlToken=tok123&relayOrigin=https%3A%2F%2Frelay.example.com")
    assertTrue(link != null)
    assertTrue(link!!.url.contains("remoteControlToken=tok123"))
    assertTrue(link.url.contains("relayOrigin=https%3A%2F%2Frelay.example.com"))
  }

  @Test
  fun rejectsGarbage() {
    assertNull(RemoteLink.parse(""))
    assertNull(RemoteLink.parse("hello world"))
    assertNull(RemoteLink.parse("a1b2c3d4e5f6g7h8i9j0")) // bare token: no longer valid on its own
    assertNull(RemoteLink.parse("https://zcode.z.ai/remote/v4"))
    assertNull(RemoteLink.parse("https://example.com/?sid=S1&hash=H1&t=1")) // wrong host
  }

  @Test
  fun acceptsAlternateOfficialHost() {
    val link = RemoteLink.parse("https://zcode.chatglm.site/remote/v4?sid=S1&hash=H1&t=5")
    assertTrue(link != null)
    assertTrue(link!!.url.startsWith("https://zcode.z.ai/remote/v4?")) // rebuilt on canonical host
  }

  @Test
  fun extractsSidMidName() {
    val link = RemoteLink.parse(
      "https://zcode.z.ai/remote/v4?sid=SID123&hash=HASH&t=42&mid=MID456&name=Work%20Mac&theme=dark",
    )
    assertTrue(link != null)
    assertEquals("SID123", link!!.deviceSid)
    assertEquals("MID456", link.deviceMid)
    assertEquals("Work Mac", link.deviceName)
    assertFalse(link.isLegacyTokenFlow)
  }
}
