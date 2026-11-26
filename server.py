#!/usr/bin/env python3
"""Reference STT server for Transcribe's EXTERNAL engine mode.

Protocol is described in the README. One segment at a time per connection:
a "start" text frame, binary PCM frames, then an "end" text frame. Replies
are {"requestId", "text", "confidence"} or {"requestId", "error"}.

    pip install websockets vosk
    python3 server.py --model /path/to/vosk-model-small-en-us-0.15 --grammar fire,light,ice
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import signal
from dataclasses import dataclass, field

import vosk
from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

logger = logging.getLogger("transcribe.server")

# what vosk returns for anything outside the grammar
UNKNOWN_TOKEN = "[unk]"

DEFAULT_SAMPLE_RATE_HZ = 16_000
DEFAULT_PORT = 2700


@dataclass
class ServerConfig:
    model: vosk.Model
    grammar_json: str
    allowed_keywords: frozenset[str]
    default_sample_rate_hz: int = DEFAULT_SAMPLE_RATE_HZ


@dataclass
class PendingSegment:
    request_id: str
    player_id: str | None
    sample_rate_hz: int
    audio: bytearray = field(default_factory=bytearray)


def build_grammar_json(words: list[str]) -> tuple[str, frozenset[str]]:
    normalized = sorted({w.strip().lower() for w in words if w.strip()})
    if not normalized:
        raise ValueError("grammar word list must not be empty")
    allowed = frozenset(normalized)
    grammar_json = json.dumps([*normalized, UNKNOWN_TOKEN])
    return grammar_json, allowed


def recognize_blocking(config: ServerConfig, segment: PendingSegment) -> tuple[str, float]:
    # blocking, call through asyncio.to_thread
    # new recognizer per segment, they aren't shareable (the Model is)
    recognizer = vosk.KaldiRecognizer(config.model, segment.sample_rate_hz, config.grammar_json)
    recognizer.SetWords(True)

    recognizer.AcceptWaveform(bytes(segment.audio))
    result = json.loads(recognizer.FinalResult())

    text = str(result.get("text", "")).strip().lower()
    if not text or text == UNKNOWN_TOKEN or text not in config.allowed_keywords:
        return "", 0.0

    word_confidences = [
        float(w["conf"]) for w in result.get("result", []) if isinstance(w, dict) and "conf" in w
    ]
    confidence = sum(word_confidences) / len(word_confidences) if word_confidences else 1.0
    return text, confidence


async def handle_start(payload: dict) -> PendingSegment:
    request_id = payload["requestId"]
    player_id = payload.get("playerId")
    sample_rate_hz = int(payload.get("sampleRate", DEFAULT_SAMPLE_RATE_HZ))
    if sample_rate_hz <= 0:
        raise ValueError(f"invalid sampleRate: {sample_rate_hz}")
    return PendingSegment(request_id=request_id, player_id=player_id, sample_rate_hz=sample_rate_hz)


async def handle_end(
    config: ServerConfig,
    connection: ServerConnection,
    segment: PendingSegment,
    end_request_id: str,
) -> None:
    if segment.request_id != end_request_id:
        await send_error(
            connection,
            end_request_id,
            f"end.requestId ({end_request_id}) did not match the in-flight segment ({segment.request_id})",
        )
        return

    if not segment.audio:
        await send_result(connection, segment.request_id, "", 0.0)
        return

    try:
        text, confidence = await asyncio.to_thread(recognize_blocking, config, segment)
    except Exception as exc:  # noqa: BLE001
        logger.exception("Recognition failed for request %s", segment.request_id)
        await send_error(connection, segment.request_id, f"recognition failed: {exc}")
        return

    logger.info(
        "request=%s player=%s -> text=%r confidence=%.3f (%d audio bytes)",
        segment.request_id,
        segment.player_id,
        text,
        confidence,
        len(segment.audio),
    )
    await send_result(connection, segment.request_id, text, confidence)


async def send_result(connection: ServerConnection, request_id: str, text: str, confidence: float) -> None:
    await connection.send(json.dumps({"requestId": request_id, "text": text, "confidence": confidence}))


async def send_error(connection: ServerConnection, request_id: str, message: str) -> None:
    logger.warning("request=%s error=%s", request_id, message)
    await connection.send(json.dumps({"requestId": request_id, "error": message}))


async def connection_handler(config: ServerConfig, connection: ServerConnection) -> None:
    peer = connection.remote_address
    logger.info("Client connected: %s", peer)
    active_segment: PendingSegment | None = None

    try:
        async for message in connection:
            if isinstance(message, str):
                try:
                    payload = json.loads(message)
                except json.JSONDecodeError:
                    logger.warning("Ignoring malformed JSON text frame from %s: %r", peer, message)
                    continue

                msg_type = payload.get("type")
                if msg_type == "start":
                    if active_segment is not None:
                        logger.warning(
                            "New 'start' (request=%s) arrived while request=%s was still in "
                            "flight on the same connection; discarding the earlier segment. "
                            "This reference server processes one segment at a time per "
                            "connection - see the module docstring.",
                            payload.get("requestId"),
                            active_segment.request_id,
                        )
                    try:
                        active_segment = await handle_start(payload)
                    except (KeyError, ValueError) as exc:
                        active_segment = None
                        await send_error(connection, payload.get("requestId", ""), f"invalid start message: {exc}")

                elif msg_type == "end":
                    request_id = payload.get("requestId", "")
                    if active_segment is None:
                        await send_error(connection, request_id, "received 'end' with no in-flight segment")
                        continue
                    await handle_end(config, connection, active_segment, request_id)
                    active_segment = None

                else:
                    logger.warning("Ignoring text frame with unknown type=%r from %s", msg_type, peer)

            else:
                if active_segment is None:
                    logger.warning("Dropping binary audio frame from %s with no active segment", peer)
                    continue
                active_segment.audio.extend(message)

    except ConnectionClosed:
        pass
    finally:
        logger.info("Client disconnected: %s", peer)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", required=True, help="Path to an unpacked Vosk model directory")
    parser.add_argument(
        "--grammar",
        default="fire,light,ice",
        help="Comma-separated words, same as engine.internal.grammar in config.yml",
    )
    parser.add_argument("--host", default="0.0.0.0", help="Interface to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Port to bind (default: {DEFAULT_PORT})")
    parser.add_argument(
        "--sample-rate",
        type=int,
        default=DEFAULT_SAMPLE_RATE_HZ,
        help=f"Sample rate if start message has none (default: {DEFAULT_SAMPLE_RATE_HZ})",
    )
    parser.add_argument("--log-level", default="INFO", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    parser.add_argument(
        "--verbose-vosk",
        action="store_true",
        help="Do not suppress Vosk's native Kaldi logging (very chatty; off by default)",
    )
    return parser.parse_args()


async def run_server() -> None:
    args = parse_args()
    logging.basicConfig(level=args.log_level, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")

    if not args.verbose_vosk:
        vosk.SetLogLevel(-1)

    logger.info("Loading Vosk model from %s ...", args.model)
    model = vosk.Model(args.model)

    grammar_words = args.grammar.split(",")
    grammar_json, allowed_keywords = build_grammar_json(grammar_words)
    logger.info("Constrained grammar: %s", grammar_json)

    config = ServerConfig(
        model=model,
        grammar_json=grammar_json,
        allowed_keywords=allowed_keywords,
        default_sample_rate_hz=args.sample_rate,
    )

    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            # windows
            pass

    async with serve(
        lambda connection: connection_handler(config, connection),
        args.host,
        args.port,
        max_size=None,  # default 1MiB cap is too small for audio
    ):
        logger.info("Transcribe external STT server listening on ws://%s:%d", args.host, args.port)
        await stop_event.wait()
        logger.info("Shutdown signal received, closing server.")


def main() -> None:
    try:
        asyncio.run(run_server())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
