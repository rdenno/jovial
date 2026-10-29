package com.rdenno.jovial.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/**
 * [DefaultExtractorsFactory] whose fragmented MP4 extractor picks the right segment index when a
 * file has several. Ganymede's live archives carry one `sidx` per track, and in long recordings
 * the audio track's index can be wrong past the 4 GB mark (missing fragments, so every later
 * offset lands mid-data). ExoPlayer seeks with whichever `sidx` it reads first, so resuming such
 * a video late in the stream failed with "Skipping atom with length > 2147483647".
 */
@OptIn(UnstableApi::class)
class SegmentIndexFixExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = wrap(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(defaults.createExtractors(uri, responseHeaders))

    @Deprecated("Deprecated in ExtractorsFactory")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        @Suppress("DEPRECATION")
        defaults.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        defaults.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map { extractor ->
        if (extractor.underlyingImplementation is FragmentedMp4Extractor) SegmentIndexFixExtractor(extractor) else extractor
    }.toTypedArray()
}

/**
 * Holds back the seek maps built from consecutive `sidx` boxes until all of them have been read,
 * then hands the player only the most complete index (most fragments, reaching furthest).
 */
@OptIn(UnstableApi::class)
private class SegmentIndexFixExtractor(private val delegate: Extractor) : Extractor {
    private lateinit var output: ExtractorOutput
    private val pendingSeekMaps = mutableListOf<SeekMap>()
    private var seekMapForwarded = false
    private val atomHeader = ByteArray(8)

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        this.output = output
        delegate.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                if (seekMapForwarded) output.seekMap(seekMap) else pendingSeekMaps += seekMap
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(input, seekPosition)
        // The extractor reads one atom per step; once the next atom is not another sidx, every
        // index in the run has been seen
        if (pendingSeekMaps.isNotEmpty() && (result != Extractor.RESULT_CONTINUE || !nextAtomIsSidx(input))) {
            output.seekMap(mostCompleteIndex(pendingSeekMaps))
            pendingSeekMaps.clear()
            seekMapForwarded = true
        }
        return result
    }

    private fun nextAtomIsSidx(input: ExtractorInput): Boolean = try {
        input.peekFully(atomHeader, 0, atomHeader.size, /* allowEndOfInput = */ true) &&
            String(atomHeader, 4, 4, Charsets.ISO_8859_1) == "sidx"
    } catch (e: EOFException) {
        false
    } finally {
        input.resetPeekPosition()
    }

    private fun mostCompleteIndex(seekMaps: List<SeekMap>): SeekMap {
        val indexes = seekMaps.filterIsInstance<ChunkIndex>()
        if (indexes.size < 2) return seekMaps.last()
        return indexes.maxWith(
            compareBy<ChunkIndex> { it.length }
                .thenBy { if (it.length > 0) it.offsets[it.length - 1] + it.sizes[it.length - 1] else 0L }
        )
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}
