package org.vulkanb.eng.scene;

import org.joml.Matrix4f;

public class Perspective {

    private static final float FOV = (float) Math.toRadians(60.0f);
    private static final float Z_FAR = 100.f;
    private static final float Z_NEAR = 0.01f;

    private Matrix4f perspectiveMatrix;

    public Perspective() {
        perspectiveMatrix = new Matrix4f();
    }

    public Matrix4f getPerspectiveMatrix() {
        return perspectiveMatrix;
    }

    public void resize(int width, int height) {
        perspectiveMatrix.identity();
        perspectiveMatrix.perspective(FOV, (float) width / (float) height, Z_NEAR, Z_FAR, true);
    }
}
