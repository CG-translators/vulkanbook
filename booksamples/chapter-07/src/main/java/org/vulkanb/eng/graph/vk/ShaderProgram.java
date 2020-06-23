package org.vulkanb.eng.graph.vk;

import org.apache.logging.log4j.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.*;
import java.nio.*;
import java.nio.file.Files;

import static org.lwjgl.vulkan.VK10.*;
import static org.vulkanb.eng.graph.vk.VulkanUtils.vkCheck;

public class ShaderProgram {

    private static final Logger LOGGER = LogManager.getLogger();

    private Device device;
    private ShaderModule[] shaderModules;

    public ShaderProgram(Device device, ShaderModuleData[] shaderModuleData) {
        try {
            this.device = device;
            int numModules = shaderModuleData != null ? shaderModuleData.length : 0;
            this.shaderModules = new ShaderModule[numModules];
            for (int i = 0; i < numModules; i++) {
                byte[] moduleContents = Files.readAllBytes(new File(shaderModuleData[i].shaderSpvFile()).toPath());
                long moduleHandle = createShaderModule(moduleContents);
                this.shaderModules[i] = new ShaderModule(shaderModuleData[i].shaderStage(), moduleHandle);
            }
        } catch (IOException excp) {
            LOGGER.error("Error reading shader files", excp);
            throw new RuntimeException(excp);
        }
    }

    public void cleanUp() {
        for (ShaderModule shaderModule : this.shaderModules) {
            vkDestroyShaderModule(this.device.getVkDevice(), shaderModule.handle(), null);
        }
    }

    private long createShaderModule(byte[] code) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pCode = stack.malloc(code.length).put(0, code);

            VkShaderModuleCreateInfo moduleCreateInfo = VkShaderModuleCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(pCode);

            LongBuffer lp = stack.mallocLong(1);
            vkCheck(vkCreateShaderModule(device.getVkDevice(), moduleCreateInfo, null, lp),
                    "Failed to create shader module");

            return lp.get(0);
        }
    }

    public ShaderModule[] getShaderModules() {
        return this.shaderModules;
    }

    public record ShaderModule(int shaderStage, long handle) {
    }

    public record ShaderModuleData(int shaderStage, String shaderSpvFile) {
    }
}