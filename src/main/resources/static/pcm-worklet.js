// Runs inside the browser's audio thread. Receives the microphone signal in tiny frames
// (128 samples at a time, at the device's rate, usually 48 kHz on iPhone), converts it to
// what Whisper wants (16 kHz, mono, 16-bit), and hands back 0.1 s chunks to the page.

const TARGET_RATE = 16000;
const CHUNK_SAMPLES = 1600; // 0.1 s at 16 kHz

class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    // `sampleRate` is provided by the audio thread: the device's actual rate
    this.ratio = sampleRate / TARGET_RATE; // e.g. 3 when the phone runs at 48 kHz
    this.pos = 0;   // how far we are towards the next output sample
    this.sum = 0;   // running total of the input samples being merged
    this.count = 0;
    this.out = new Int16Array(CHUNK_SAMPLES);
    this.outLen = 0;
  }

  process(inputs) {
    const channel = inputs[0][0]; // first input, first channel (mono is all we need)
    if (!channel) return true;    // no data this round; keep the processor alive

    for (let i = 0; i < channel.length; i++) {
      // Downsample by averaging each group of input samples into one output sample.
      // Averaging also smooths away high pitches 16 kHz can't represent, which would
      // otherwise fold back into the audio as noise.
      this.sum += channel[i];
      this.count++;
      this.pos++;

      if (this.pos >= this.ratio) {
        this.pos -= this.ratio;
        const value = this.sum / this.count; // -1.0 .. 1.0
        this.sum = 0;
        this.count = 0;

        // 16-bit samples: -32767 .. 32767. Half the size of floats, plenty for speech.
        const clamped = Math.max(-1, Math.min(1, value));
        this.out[this.outLen++] = clamped * 0x7fff;

        if (this.outLen === CHUNK_SAMPLES) {
          // Transfer (not copy) the buffer to the page, then start a fresh one
          this.port.postMessage(this.out.buffer, [this.out.buffer]);
          this.out = new Int16Array(CHUNK_SAMPLES);
          this.outLen = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor("pcm-capture", PcmCapture);