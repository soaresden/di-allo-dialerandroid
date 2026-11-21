package fr.soaresden.diallo.rec

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.FileOutputStream

/**
 * Encodeur MP3 (LAME porté en Java : jump3r, LGPL). PCM 16 bits mono → MP3 CBR.
 * On assemble les modules nous-mêmes : l'enveloppe « lowlevel » de jump3r dépend de javax.sound,
 * absent d'Android.
 */
class Mp3Writer(file: File, hz: Int, kbps: Int = 32) : AutoCloseable {
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private val out = FileOutputStream(file)
    private val mp3 = ByteArray(16 * 1024 + 7200)
    private var ints = IntArray(0)

    init {
        val ga = GainAnalysis(); val bs = BitStream(); val p = Presets(); val qupvt = QuantizePVT(); val qu = Quantize()
        val vbr = VBRTag(); val ver = Version(); val id3 = ID3Tag(); val rv = Reservoir(); val tak = Takehiro()
        val parse = Parse(); val mpg = MPGLib(); val intf = Interface(); val common = Common(); val gaud = GetAudio()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)
        gfp = lame.lame_init()
        gfp.num_channels = 1
        gfp.in_samplerate = hz
        gfp.out_samplerate = hz
        gfp.mode = MPEGMode.MONO
        gfp.VBR = VbrMode.vbr_off
        gfp.brate = kbps
        gfp.quality = 5
        gfp.bWriteVbrTag = false
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        check(lame.lame_init_params(gfp) >= 0) { "MP3 : réglages refusés" }
    }

    /** [pcm] : octets PCM 16 bits little-endian, [n] octets (pair). */
    fun write(pcm: ByteArray, n: Int) {
        val count = n / 2
        if (ints.size < count) ints = IntArray(count)
        for (i in 0 until count) ints[i] = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)) shl 16
        var off = 0
        while (off < count) {   // par paquets : le tampon MP3 a une taille finie
            val k = minOf(4096, count - off)
            val chunk = if (off == 0 && k == count) ints else ints.copyOfRange(off, off + k)
            val w = lame.lame_encode_buffer_int(gfp, chunk, chunk, k, mp3, 0, mp3.size)
            if (w > 0) out.write(mp3, 0, w)
            off += k
        }
    }

    override fun close() {
        try {
            val w = lame.lame_encode_flush(gfp, mp3, 0, mp3.size)
            if (w > 0) out.write(mp3, 0, w)
            lame.lame_close(gfp)
        } finally { out.close() }
    }
}
