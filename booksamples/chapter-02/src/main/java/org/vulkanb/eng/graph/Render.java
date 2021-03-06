package org.vulkanb.eng.graph;

import org.vulkanb.eng.*;
import org.vulkanb.eng.graph.vk.Instance;
import org.vulkanb.eng.scene.Scene;

public class Render {

    private Instance instance;

    public void cleanup() {
        this.instance.cleanup();
    }

    public void init(Window window) {
        EngineProperties engProps = EngineProperties.getInstance();
        this.instance = new Instance(engProps.isValidate());
    }

    public void render(Window window, Scene scene) {
        // To be implemented
    }
}
