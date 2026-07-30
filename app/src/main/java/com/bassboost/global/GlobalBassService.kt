// content.js — Analizador de Ritmo y Bajo Profundo Dinámico (CarPlayer Pro)
(function () {
  const KEYS = {
    bass: 'bassLevel',       // 0 - 29
    tweeter: 'tweeterLevel', // 0 - 20
    dolby: 'dolbyLevel',     // 0 - 15
    mode6d: 'mode6d'         // boolean
  };

  let audioCtx = null;
  let currentBass = 0;
  let currentTweeter = 0;
  let currentDolby = 0;
  let mode6dEnabled = false;

  const processed = new WeakSet();
  const engines = [];

  function getAudioContext() {
    if (!audioCtx) {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    }
    return audioCtx;
  }

  function attach(mediaEl) {
    if (!mediaEl || processed.has(mediaEl)) return;
    if (mediaEl.tagName !== 'VIDEO' && mediaEl.tagName !== 'AUDIO') return;

    try {
      const ctx = getAudioContext();
      const source = ctx.createMediaElementSource(mediaEl);
      
      // Analizador de espectro optimizado para detección precisa de beats
      const analyzer = ctx.createAnalyser();
      analyzer.fftSize = 256;
      analyzer.smoothingTimeConstant = 0.3;

      const masterInput = ctx.createGain();
      masterInput.gain.value = 0.35;

      // =========================================================================
      // CANAL DE BAJO PROFUNDO PARALELO (Sub-graves limpios sin bajo seco)
      // =========================================================================
      const bassSplitter = ctx.createGain();
      bassSplitter.gain.value = 1.0;

      const subsonicFilter = ctx.createBiquadFilter();
      subsonicFilter.type = 'highpass';
      subsonicFilter.frequency.value = 25; 
      subsonicFilter.Q.value = 1.0;

      const bassLowpass = ctx.createBiquadFilter();
      bassLowpass.type = 'lowpass';
      bassLowpass.frequency.value = 110; // Corte estricto para que la voz no se hunda
      bassLowpass.Q.value = 1.0;

      const subEngine = ctx.createBiquadFilter();
      subEngine.type = 'lowshelf';
      subEngine.frequency.value = 40;
      subEngine.gain.value = 0;

      const deepEngine = ctx.createBiquadFilter();
      deepEngine.type = 'peaking';
      deepEngine.frequency.value = 35;
      deepEngine.Q.value = 1.4;
      deepEngine.gain.value = 0;

      // Compresor blindado para evitar chasquidos y saturación
      const bassCompressor = ctx.createDynamicsCompressor();
      bassCompressor.threshold.value = -20.0; 
      bassCompressor.knee.value = 12.0;       
      bassCompressor.ratio.value = 12.0;      
      bassCompressor.attack.value = 0.002;    
      bassCompressor.release.value = 0.25;

      const bassOutput = ctx.createGain();
      bassOutput.gain.value = 1.0;

      // =========================================================================
      // CANAL PRINCIPAL DE VOZ (Limpia y cristalina)
      // =========================================================================
      const mainSplitter = ctx.createGain();
      mainSplitter.gain.value = 1.0;

      const clarity = ctx.createBiquadFilter();
      clarity.type = 'peaking';
      clarity.frequency.value = 3000;
      clarity.gain.value = 5;

      const air = ctx.createBiquadFilter();
      air.type = 'highshelf';
      air.frequency.value = 12000;
      air.gain.value = 6;

      const mainOutput = ctx.createGain();
      mainOutput.gain.value = 1.0;

      // =========================================================================
      // MASTER Y EFECTOS ADICIONALES (Tweeter, Dolby, 6D)
      // =========================================================================
      const masterSum = ctx.createGain();
      masterSum.gain.value = 1.0;

      const tweeterNode = ctx.createBiquadFilter();
      tweeterNode.type = 'highshelf';
      tweeterNode.frequency.value = 10000;
      tweeterNode.gain.value = 0;

      const dolbyNode = ctx.createBiquadFilter();
      dolbyNode.type = 'peaking';
      dolbyNode.frequency.value = 6000;
      dolbyNode.Q.value = 1.0;
      dolbyNode.gain.value = 0;

      const panner6D = ctx.createStereoPanner ? ctx.createStereoPanner() : null;

      const limiter = ctx.createDynamicsCompressor();
      limiter.threshold.value = -12.0;
      limiter.ratio.value = 20;

      // Enrutamiento
      source.connect(masterInput);

      masterInput.connect(bassSplitter);
      bassSplitter.connect(subsonicFilter);
      subsonicFilter.connect(bassLowpass);
      bassLowpass.connect(subEngine);
      subEngine.connect(deepEngine);
      deepEngine.connect(bassCompressor);
      bassCompressor.connect(bassOutput);
      bassOutput.connect(masterSum);

      masterInput.connect(mainSplitter);
      mainSplitter.connect(clarity);
      clarity.connect(air);
      air.connect(mainOutput);
      mainOutput.connect(masterSum);

      masterSum.connect(tweeterNode);
      tweeterNode.connect(dolbyNode);

      if (panner6D) {
        dolbyNode.connect(panner6D);
        panner6D.connect(limiter);
      } else {
        dolbyNode.connect(limiter);
      }

      limiter.connect(analyzer);
      analyzer.connect(ctx.destination);

      processed.add(mediaEl);
      const engine = {
        mediaEl, analyzer, masterInput, subEngine, deepEngine, tweeterNode, dolbyNode, panner6D,
        lastBeatTime: 0, beatThreshold: 0
      };
      engines.push(engine);

      applyBass(engine, currentBass);
      applyTweeter(engine, currentTweeter);
      applyDolby(engine, currentDolby);

      console.log('[CarPlayer Pro] Motor con Detección de Ritmo Activa en:', mediaEl.tagName);
    } catch (err) {
      console.warn('[CarPlayer Pro] Error al enganchar elemento multimedia:', err.message);
    }
  }

  function applyBass(engine, v) {
    if (!audioCtx) return;
    const boost = v * 1.4;
    engine.deepEngine.gain.setTargetAtTime(boost, audioCtx.currentTime, 0.2);
    engine.subEngine.gain.setTargetAtTime(boost * 0.8, audioCtx.currentTime, 0.2);

    const vol = 0.35 - v * 0.005;
    engine.masterInput.gain.setTargetAtTime(Math.max(vol, 0.1), audioCtx.currentTime, 0.2);
  }

  function applyTweeter(engine, v) {
    if (!audioCtx) return;
    engine.tweeterNode.gain.setTargetAtTime(v * 1.2, audioCtx.currentTime, 0.1);
  }

  function applyDolby(engine, v) {
    if (!audioCtx) return;
    engine.dolbyNode.gain.setTargetAtTime(v, audioCtx.currentTime, 0.1);
  }

  function setBass(v) {
    currentBass = Math.max(0, Math.min(29, v));
    engines.forEach((e) => applyBass(e, currentBass));
  }
  function setTweeter(v) {
    currentTweeter = Math.max(0, Math.min(20, v));
    engines.forEach((e) => applyTweeter(e, currentTweeter));
  }
  function setDolby(v) {
    currentDolby = Math.max(0, Math.min(15, v));
    engines.forEach((e) => applyDolby(e, currentDolby));
  }
  function setMode6d(enabled) {
    mode6dEnabled = !!enabled;
  }

  // ALGORITMO DE DETECCIÓN DE RITMO EN TIEMPO REAL (BEAT DETECTION)
  function detectBeats() {
    if (audioCtx && currentBass > 0) {
      engines.forEach((e) => {
        const dataArray = new Uint8Array(e.analyzer.frequencyBinCount);
        e.analyzer.getByteFrequencyData(dataArray);

        // Medir energía exclusiva de las frecuencias bajas (primeras 5 bandas)
        let bassEnergy = 0;
        for (let i = 0; i < 5; i++) {
          bassEnergy += dataArray[i];
        }
        bassEnergy = bassEnergy / 5;

        // Umbral adaptativo dinámico al ritmo de la canción
        e.beatThreshold = e.beatThreshold * 0.92 + bassEnergy * 0.08;

        // Si la energía supera el umbral, se dispara un golpe profundo instantáneo en el bajo
        if (bassEnergy > e.beatThreshold * 1.25 && audioCtx.currentTime - e.lastBeatTime > 0.14) {
          const punchGain = (currentBass * 1.4) * 1.35;
          e.deepEngine.gain.setTargetAtTime(punchGain, audioCtx.currentTime, 0.02);
          
          setTimeout(() => {
            if (e.deepEngine) {
              e.deepEngine.gain.setTargetAtTime(currentBass * 1.4, audioCtx.currentTime, 0.12);
            }
          }, 70);

          e.lastBeatTime = audioCtx.currentTime;
        }
      });
    }
    requestAnimationFrame(detectBeats);
  }
  detectBeats();

  function tick6D() {
    if (audioCtx) {
      engines.forEach((e) => {
        if (!e.panner6D) return;
        if (mode6dEnabled && !e.mediaEl.paused) {
          const t = audioCtx.currentTime;
          const panValue = Math.sin((2 * Math.PI * t) / 8);
          e.panner6D.pan.setTargetAtTime(panValue, audioCtx.currentTime, 0.05);
        } else {
          e.panner6D.pan.setValueAtTime(0, audioCtx.currentTime);
        }
      });
    }
    requestAnimationFrame(tick6D);
  }
  tick6D();

  function scanForMedia() {
    document.querySelectorAll('video, audio').forEach(attach);
  }

  const observer = new MutationObserver(() => scanForMedia());
  observer.observe(document.documentElement, { childList: true, subtree: true });
  scanForMedia();

  function resumeOnGesture() {
    if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
  }
  ['click', 'keydown', 'touchstart'].forEach(evt =>
    document.addEventListener(evt, resumeOnGesture, { passive: true })
  );

  chrome.storage.sync.get([KEYS.bass, KEYS.tweeter, KEYS.dolby, KEYS.mode6d], (result) => {
    setBass(result[KEYS.bass] ?? 0);
    setTweeter(result[KEYS.tweeter] ?? 0);
    setDolby(result[KEYS.dolby] ?? 0);
    setMode6d(result[KEYS.mode6d] ?? false);
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'sync') return;
    if (changes[KEYS.bass]) setBass(changes[KEYS.bass].newValue ?? 0);
    if (changes[KEYS.tweeter]) setTweeter(changes[KEYS.tweeter].newValue ?? 0);
    if (changes[KEYS.dolby]) setDolby(changes[KEYS.dolby].newValue ?? 0);
    if (changes[KEYS.mode6d]) setMode6d(changes.mode6d.newValue ?? false);
  });
})();
