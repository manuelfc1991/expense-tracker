package com.manuel.ours.sync

import com.manuel.ours.data.sync.SafFolderTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Filenames are the entire addressing scheme for folder sync — there is no database
 * on the other side, just files in a directory. If a name stops round-tripping, a
 * device silently reads its own log as a peer's, or ignores a real peer entirely.
 *
 * Cloud providers do not honour requested display names as reliably as you'd hope,
 * which is what most of these cases are about.
 */
class SafFolderNamingTest {

    private val deviceId = "6986f741-e7c0-4ba1-9d3e-2f1a7c4b8e55"

    @Test
    fun `a filename round-trips back to its device id`() {
        val name = SafFolderTransport.fileNameFor(deviceId)
        assertThat(SafFolderTransport.deviceIdFromFileName(name)).isEqualTo(deviceId)
    }

    @Test
    fun `the name is stable across calls`() {
        assertThat(SafFolderTransport.fileNameFor(deviceId))
            .isEqualTo(SafFolderTransport.fileNameFor(deviceId))
    }

    @Test
    fun `a provider-appended extension still resolves`() {
        // Drive occasionally hands back its own extension on upload.
        assertThat(SafFolderTransport.deviceIdFromFileName("device-$deviceId.jsonl.txt"))
            .isEqualTo(deviceId)
    }

    @Test
    fun `a conflict-renamed copy still resolves to the same device`() {
        // "device-abc.jsonl (1)" appears when two writes race in Drive. Treating that
        // as an unknown peer would leave the real log permanently unread.
        assertThat(SafFolderTransport.deviceIdFromFileName("device-$deviceId.jsonl (1)"))
            .isEqualTo(deviceId)
    }

    @Test
    fun `unrelated files in the folder are ignored`() {
        listOf(
            "notes.txt",
            "IMG_20260731.jpg",
            "device.jsonl",
            "",
            "somedevice-abc.jsonl",
        ).forEach {
            assertThat(SafFolderTransport.deviceIdFromFileName(it)).isNull()
        }
    }

    @Test
    fun `a prefix with no id is rejected rather than returning empty`() {
        // An empty device id would compare equal to nothing and be pulled as a peer,
        // feeding a garbage log into the merge.
        assertThat(SafFolderTransport.deviceIdFromFileName("device-.jsonl")).isNull()
        assertThat(SafFolderTransport.deviceIdFromFileName("device-")).isNull()
    }

    @Test
    fun `two devices get distinct filenames`() {
        val other = "11112222-3333-4444-5555-666677778888"
        assertThat(SafFolderTransport.fileNameFor(deviceId))
            .isNotEqualTo(SafFolderTransport.fileNameFor(other))
    }
}
