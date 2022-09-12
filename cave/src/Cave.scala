/*
 *   __   __     __  __     __         __
 *  /\ "-.\ \   /\ \/\ \   /\ \       /\ \
 *  \ \ \-.  \  \ \ \_\ \  \ \ \____  \ \ \____
 *   \ \_\\"\_\  \ \_____\  \ \_____\  \ \_____\
 *    \/_/ \/_/   \/_____/   \/_____/   \/_____/
 *   ______     ______       __     ______     ______     ______
 *  /\  __ \   /\  == \     /\ \   /\  ___\   /\  ___\   /\__  _\
 *  \ \ \/\ \  \ \  __<    _\_\ \  \ \  __\   \ \ \____  \/_/\ \/
 *   \ \_____\  \ \_____\ /\_____\  \ \_____\  \ \_____\    \ \_\
 *    \/_____/   \/_____/ \/_____/   \/_____/   \/_____/     \/_/
 *
 * https://joshbassett.info
 * https://twitter.com/nullobject
 * https://github.com/nullobject
 *
 * Copyright (c) 2022 Josh Bassett
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cave

import arcadia._
import arcadia.gfx._
import arcadia.mem._
import arcadia.mem.ddr.DDR
import arcadia.mem.sdram.{SDRAM, SDRAMIO}
import arcadia.mister._
import cave.fb._
import cave.main.Main
import cave.snd.Sound
import chisel3._
import chisel3.experimental.FlatIO

/**
 * The top-level module.
 *
 * This module abstracts the rest of the arcade hardware from MiSTer-specific things (e.g. memory
 * subsystem) that are not part of the original arcade hardware design.
 */
class Cave extends Module {
  val io = FlatIO(new Bundle {
    /** CPU clock */
    val cpuClock = Input(Clock())
    /** CPU reset */
    val cpuReset = Input(Bool())
    /** Video clock */
    val videoClock = Input(Clock())
    /** Video reset */
    val videoReset = Input(Bool())
    /** Options port */
    val options = OptionsIO()
    /** Player port */
    val player = Vec(2, PlayerIO())
    /** IOCTL port */
    val ioctl = IOCTL()
    /** LED port */
    val led = LEDIO()
    /** Frame buffer control port */
    val frameBufferCtrl = FrameBufferCtrlIO()
    /** Video port */
    val video = VideoIO()
    /** RGB output */
    val rgb = Output(UInt(Config.RGB_WIDTH.W))
    /** Audio port */
    val audio = Output(SInt(Config.AUDIO_SAMPLE_WIDTH.W))
    /** SDRAM control port */
    val sdram = SDRAMIO(Config.sdramConfig)
    /** DDR port */
    val ddr = BurstMemIO(Config.ddrConfig)
  })

  io.ioctl.default()

  // The game configuration register is latched when data is written to the IOCTL (i.e. the game
  // index is set by the MRA file).
  val gameConfigReg = {
    val gameConfig = Reg(GameConfig())
    val latched = RegInit(false.B)
    when(io.ioctl.download && io.ioctl.wr && io.ioctl.index === IOCTL.GAME_INDEX.U) {
      gameConfig := GameConfig(io.ioctl.dout(OptionsIO.GAME_INDEX_WIDTH - 1, 0))
      latched := true.B
    }
    // Default to the game configuration set in the options
    when(Util.falling(io.ioctl.download) && !latched) {
      gameConfig := GameConfig(io.options.gameIndex)
      latched := true.B
    }
    gameConfig
  }

  // Connect IOCTL to DIPs register file
  val dipsRegs = Module(new RegisterFile(IOCTL.DATA_WIDTH, Config.DIPS_REGS_COUNT))
  dipsRegs.io.mem <> io.ioctl.dips
    .mapAddr { a => (a >> 1).asUInt } // convert from byte address
    .asMemIO

  // DDR controller
  val ddr = Module(new DDR(Config.ddrConfig))
  ddr.io.ddr <> io.ddr

  // SDRAM controller
  val sdram = Module(new SDRAM(Config.sdramConfig))
  sdram.io.sdram <> io.sdram

  // Memory subsystem
  val memSys = Module(new MemSys)
  memSys.io.gameConfig <> gameConfigReg
  memSys.io.prog.rom <> io.ioctl.rom
  memSys.io.prog.nvram <> io.ioctl.nvram
  memSys.io.prog.done := Util.falling(io.ioctl.download) && io.ioctl.index === IOCTL.ROM_INDEX.U
  memSys.io.ddr <> ddr.io.mem
  memSys.io.sdram <> sdram.io.mem

  // Video subsystem
  val videoSys = Module(new VideoSys)
  videoSys.io.videoClock := io.videoClock
  videoSys.io.videoReset := io.videoReset
  videoSys.io.prog.video <> io.ioctl.video
  videoSys.io.prog.done := Util.falling(io.ioctl.download) && io.ioctl.index === IOCTL.VIDEO_INDEX.U
  videoSys.io.options <> io.options

  // Main PCB
  val main = withClockAndReset(io.cpuClock, io.cpuReset || !memSys.io.ready) { Module(new Main) }
  main.io.videoClock := io.videoClock
  main.io.gameConfig := gameConfigReg
  main.io.options := io.options
  main.io.dips := dipsRegs.io.regs
  main.io.player <> io.player
  main.io.video := videoSys.io.video
  main.io.progRom <> Crossing.freeze(io.cpuClock, memSys.io.progRom)
  main.io.eeprom <> Crossing.freeze(io.cpuClock, memSys.io.eeprom)
  main.io.layerTileRom(0) <> Crossing.syncronize(io.videoClock, memSys.io.layerTileRom(0))
  main.io.layerTileRom(1) <> Crossing.syncronize(io.videoClock, memSys.io.layerTileRom(1))
  main.io.layerTileRom(2) <> Crossing.syncronize(io.videoClock, memSys.io.layerTileRom(2))
  main.io.spriteTileRom <> memSys.io.spriteTileRom

  // Sound PCB
  val sound = Module(new Sound)
  sound.io.gameConfig := gameConfigReg
  sound.io.ctrl <> main.io.soundCtrl
  sound.io.rom <> memSys.io.soundRom

  // Sprite frame buffer
  val spriteFrameBuffer = Module(new SpriteFrameBuffer)
  spriteFrameBuffer.io.videoClock := io.videoClock
  spriteFrameBuffer.io.enable := memSys.io.ready
  spriteFrameBuffer.io.swap := main.io.spriteFrameBufferSwap
  spriteFrameBuffer.io.video := videoSys.io.video
  spriteFrameBuffer.io.lineBuffer <> main.io.spriteLineBuffer
  spriteFrameBuffer.io.frameBuffer <> main.io.spriteFrameBuffer
  spriteFrameBuffer.io.ddr <> memSys.io.spriteFrameBuffer

  // System frame buffer
  val systemFrameBuffer = Module(new SystemFrameBuffer)
  systemFrameBuffer.io.videoClock := io.videoClock
  systemFrameBuffer.io.enable := memSys.io.ready
  systemFrameBuffer.io.rotate := io.options.rotate
  systemFrameBuffer.io.forceBlank := !memSys.io.ready
  systemFrameBuffer.io.video := videoSys.io.video
  systemFrameBuffer.io.frameBufferCtrl <> io.frameBufferCtrl
  systemFrameBuffer.io.frameBuffer <> main.io.systemFrameBuffer
  systemFrameBuffer.io.ddr <> memSys.io.systemFrameBuffer

  // Video output
  io.video := videoSys.io.video
  io.rgb := main.io.rgb

  // Audio output
  io.audio := sound.io.audio

  // System LED outputs
  io.led.power := false.B
  io.led.disk := io.ioctl.download
  io.led.user := memSys.io.ready
}
