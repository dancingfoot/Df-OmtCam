#!/usr/bin/env node
/**
 * OMT (Open Media Transport) & RTP Node.js Test Receiver for PC
 *
 * Usage:
 *   node omt_receiver.js [port]
 */

const dgram = require('dgram');

const port = process.argv[2] ? parseInt(process.argv[2]) : 9998;
const server = dgram.createSocket('udp4');

const OMT_MAGIC = 0x4F4D;

let totalPackets = 0;
let totalBytes = 0;
let frames = 0;
let lastTime = Date.now();
let lastBytes = 0;

server.on('error', (err) => {
  console.error(`Socket error:\n${err.stack}`);
  server.close();
});

server.on('message', (msg, rinfo) => {
  totalPackets++;
  totalBytes += msg.length;

  if (msg.length >= 20 && msg.readUInt16BE(0) === OMT_MAGIC) {
    const flags = msg.readUInt16BE(6);
    const isEnd = (flags & 0x04) !== 0;
    if (isEnd) frames++;
  }

  const now = Date.now();
  if (now - lastTime >= 1000) {
    const elapsedSec = (now - lastTime) / 1000;
    const bytesDiff = totalBytes - lastBytes;
    const kbps = Math.round((bytesDiff * 8) / (elapsedSec * 1000));
    const fps = (frames / elapsedSec).toFixed(1);

    process.stdout.write(
      `\r[LIVE] From ${rinfo.address}:${rinfo.port} | Bitrate: ${kbps} kbps | FPS: ${fps} | Packets: ${totalPackets} | Size: ${(totalBytes / (1024 * 1024)).toFixed(2)} MB`
    );

    frames = 0;
    lastTime = now;
    lastBytes = totalBytes;
  }
});

server.on('listening', () => {
  const address = server.address();
  console.log('====================================================');
  console.log(` OMT Test Receiver listening on UDP ${address.address}:${address.port}`);
  console.log(` Configure OmtCam on your phone to send to this PC`);
  console.log('====================================================\n');
});

server.bind(port);
