"""Server TTS audio delivery test service.

A standalone, loopback-only HTTP service that hands one fixed, pre-generated
MP3 to an already paired watch over private HTTPS. There is no live TTS
synthesis, no user file selection, no API key and no redirect: the only
sensitive endpoint is authenticated per request with a one-use nonce.

Runtime bind (staging): ``127.0.0.1:18766`` behind host-owned Tailscale Serve
on HTTPS port ``8445``; the pre-existing gadget origin (``8444``) is untouched.
The service is not deployed by this task.
"""
