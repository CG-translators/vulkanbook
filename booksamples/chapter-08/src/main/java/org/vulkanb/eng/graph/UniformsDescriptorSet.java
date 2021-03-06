package org.vulkanb.eng.graph;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.vulkanb.eng.graph.vk.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.vulkanb.eng.graph.vk.VulkanUtils.vkCheck;

public class UniformsDescriptorSet {

    private long vkDescriptorSet;

    public UniformsDescriptorSet(DescriptorPool descriptorPool, DescriptorSetLayout descriptorSetLayout,
                                 VulkanBuffer projMatrixBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Device device = descriptorPool.getDevice();
            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
            pDescriptorSetLayout.put(0, descriptorSetLayout.getVkDescriptorLayout());
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool.getVkDescriptorPool())
                    .pSetLayouts(pDescriptorSetLayout);

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            vkCheck(vkAllocateDescriptorSets(device.getVkDevice(), allocInfo, pDescriptorSet),
                    "Failed to create descriptor set");

            vkDescriptorSet = pDescriptorSet.get(0);

            VkDescriptorBufferInfo.Buffer projBufferInfo = VkDescriptorBufferInfo.callocStack(1, stack)
                    .buffer(projMatrixBuffer.getBuffer())
                    .offset(0)
                    .range(projMatrixBuffer.getRequestedSize());

            VkWriteDescriptorSet.Buffer descrBuffer = VkWriteDescriptorSet.callocStack(1, stack);

            // Projection matrix
            descrBuffer.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(projBufferInfo);

            vkUpdateDescriptorSets(device.getVkDevice(), descrBuffer, null);
        }
    }

    public long getVkDescriptorSet() {
        return vkDescriptorSet;
    }

}
