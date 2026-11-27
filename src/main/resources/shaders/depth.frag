#version 330 core

// Deliberately empty: the depth pass writes gl_FragDepth implicitly and the FBO has no colour
// attachment at all, so there is nothing to output. A shader that wrote a colour would be computing a
// value with nowhere to go.
void main() {
}
