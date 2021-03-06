package org.vulkanb.eng.graph;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.*;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import org.vulkanb.eng.EngineProperties;
import org.vulkanb.eng.graph.vk.Queue;
import org.vulkanb.eng.graph.vk.*;
import org.vulkanb.eng.scene.*;

import java.nio.*;
import java.util.*;

import static org.lwjgl.vulkan.VK11.*;
import static org.vulkanb.eng.graph.vk.VulkanUtils.vkCheck;

public class ForwardRenderActivity {

    private static final String FRAGMENT_SHADER_FILE_GLSL = "resources/shaders/fwd_fragment.glsl";
    private static final String FRAGMENT_SHADER_FILE_SPV = FRAGMENT_SHADER_FILE_GLSL + ".spv";
    private static final int MAX_DESCRIPTORS = 100;
    private static final String VERTEX_SHADER_FILE_GLSL = "resources/shaders/fwd_vertex.glsl";
    private static final String VERTEX_SHADER_FILE_SPV = VERTEX_SHADER_FILE_GLSL + ".spv";
    private CommandBuffer[] commandBuffers;
    private ImageView[] depthImageViews;
    private Image[] depthImages;
    private DescriptorPool descriptorPool;
    private DescriptorSetLayout[] descriptorSetLayouts;
    private Map<String, TextureDescriptorSet> descriptorSetMap;
    private Device device;
    private Fence[] fences;
    private FrameBuffer[] frameBuffers;
    private ShaderProgram fwdShaderProgram;
    private Pipeline pipeLine;
    private PipelineCache pipelineCache;
    private VulkanBuffer projMatrixUniform;
    private SwapChainRenderPass renderPass;
    private SwapChain swapChain;
    private TextureDescriptorSetLayout textureDescriptorSetLayout;
    private TextureSampler textureSampler;
    private UniformsDescriptorSet uniformsDescriptorSet;
    private UniformsDescriptorSetLayout uniformsDescriptorSetLayout;

    public ForwardRenderActivity(SwapChain swapChain, CommandPool commandPool, PipelineCache pipelineCache, Scene scene) {
        this.swapChain = swapChain;
        this.pipelineCache = pipelineCache;
        device = swapChain.getDevice();

        int numImages = swapChain.getImageViews().length;
        createDepthImages();
        renderPass = new SwapChainRenderPass(swapChain, depthImages[0].getFormat());
        createFrameBuffers();

        EngineProperties engineProperties = EngineProperties.getInstance();
        if (engineProperties.isShaderRecompilation()) {
            ShaderCompiler.compileShaderIfChanged(VERTEX_SHADER_FILE_GLSL, Shaderc.shaderc_glsl_vertex_shader);
            ShaderCompiler.compileShaderIfChanged(FRAGMENT_SHADER_FILE_GLSL, Shaderc.shaderc_glsl_fragment_shader);
        }
        fwdShaderProgram = new ShaderProgram(device, new ShaderProgram.ShaderModuleData[]
                {
                        new ShaderProgram.ShaderModuleData(VK_SHADER_STAGE_VERTEX_BIT, VERTEX_SHADER_FILE_SPV),
                        new ShaderProgram.ShaderModuleData(VK_SHADER_STAGE_FRAGMENT_BIT, FRAGMENT_SHADER_FILE_SPV),
                });

        uniformsDescriptorSetLayout = new UniformsDescriptorSetLayout(device, 0);
        textureDescriptorSetLayout = new TextureDescriptorSetLayout(device, 0);
        descriptorSetLayouts = new DescriptorSetLayout[]{
                uniformsDescriptorSetLayout,
                textureDescriptorSetLayout,
        };

        Pipeline.PipeLineCreationInfo pipeLineCreationInfo = new Pipeline.PipeLineCreationInfo(
                renderPass.getVkRenderPass(), fwdShaderProgram, 1, true, GraphConstants.MAT4X4_SIZE * 2,
                new VertexBufferStructure(), descriptorSetLayouts);
        pipeLine = new Pipeline(this.pipelineCache, pipeLineCreationInfo);
        pipeLineCreationInfo.cleanup();

        commandBuffers = new CommandBuffer[numImages];
        fences = new Fence[numImages];
        for (int i = 0; i < numImages; i++) {
            commandBuffers[i] = new CommandBuffer(commandPool, true, false);
            fences[i] = new Fence(device, true);
        }
        descriptorPool = new DescriptorPool(device, MAX_DESCRIPTORS, 0);
        descriptorSetMap = new HashMap<>();
        textureSampler = new TextureSampler(device, 1);
        projMatrixUniform = new VulkanBuffer(device, GraphConstants.MAT4X4_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        uniformsDescriptorSet = new UniformsDescriptorSet(descriptorPool, uniformsDescriptorSetLayout, projMatrixUniform);
        setProjectionUniform(scene.getPerspective().getPerspectiveMatrix());
    }

    public void cleanup() {
        projMatrixUniform.cleanup();
        textureSampler.cleanup();
        descriptorPool.cleanup();
        pipeLine.cleanup();
        Arrays.stream(descriptorSetLayouts).forEach(DescriptorSetLayout::cleanup);
        Arrays.stream(depthImageViews).forEach(ImageView::cleanup);
        Arrays.stream(depthImages).forEach(Image::cleanup);
        fwdShaderProgram.cleanup();
        Arrays.stream(frameBuffers).forEach(FrameBuffer::cleanup);
        renderPass.cleanup();
        Arrays.stream(commandBuffers).forEach(CommandBuffer::cleanup);
        Arrays.stream(fences).forEach(Fence::cleanup);
    }

    private void createDepthImages() {
        int numImages = swapChain.getNumImages();
        VkExtent2D swapChainExtent = swapChain.getSwapChainExtent();
        int mipLevels = 1;
        depthImages = new Image[numImages];
        depthImageViews = new ImageView[numImages];
        for (int i = 0; i < numImages; i++) {
            depthImages[i] = new Image(device, swapChainExtent.width(), swapChainExtent.height(),
                    VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, 1, mipLevels);
            depthImageViews[i] = new ImageView(device, depthImages[i].getVkImage(),
                    depthImages[i].getFormat(), VK_IMAGE_ASPECT_DEPTH_BIT, mipLevels);
        }
    }

    private void createFrameBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExtent2D swapChainExtent = swapChain.getSwapChainExtent();
            ImageView[] imageViews = swapChain.getImageViews();
            int numImages = imageViews.length;

            LongBuffer pAttachments = stack.mallocLong(2);
            frameBuffers = new FrameBuffer[numImages];
            for (int i = 0; i < numImages; i++) {
                pAttachments.put(0, imageViews[i].getVkImageView());
                pAttachments.put(1, depthImageViews[i].getVkImageView());
                frameBuffers[i] = new FrameBuffer(device, swapChainExtent.width(), swapChainExtent.height(),
                        pAttachments, renderPass.getVkRenderPass());
            }
        }
    }

    public void meshUnLoaded(VulkanMesh vulkanMesh) {
        descriptorSetMap.remove(vulkanMesh.getTextureId());
    }

    public void meshesLoaded(VulkanMesh[] meshes, TextureCache textureCache) {
        for (VulkanMesh vulkanMesh : meshes) {
            TextureDescriptorSet textureDescriptorSet = descriptorSetMap.get(vulkanMesh.getTextureId());
            if (textureDescriptorSet == null) {
                Texture texture = textureCache.getTexture(vulkanMesh.getTextureId());
                textureDescriptorSet = new TextureDescriptorSet(descriptorPool, textureDescriptorSetLayout,
                        texture, textureSampler, 0);
                descriptorSetMap.put(vulkanMesh.getTextureId(), textureDescriptorSet);
            }
        }
    }

    public void recordCommandBuffers(List<VulkanMesh> meshes, Scene scene) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExtent2D swapChainExtent = swapChain.getSwapChainExtent();
            int width = swapChainExtent.width();
            int height = swapChainExtent.height();
            int idx = swapChain.getCurrentFrame();

            Fence fence = fences[idx];
            CommandBuffer commandBuffer = commandBuffers[idx];
            FrameBuffer frameBuffer = frameBuffers[idx];

            fence.fenceWait();
            fence.reset();

            commandBuffer.reset();
            VkClearValue.Buffer clearValues = VkClearValue.callocStack(2, stack);
            clearValues.apply(0, v -> v.color().float32(0, 0.5f).float32(1, 0.7f).float32(2, 0.9f).float32(3, 1));
            clearValues.apply(1, v -> v.depthStencil().depth(1.0f));

            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass.getVkRenderPass())
                    .pClearValues(clearValues)
                    .renderArea(a -> a.extent().set(width, height))
                    .framebuffer(frameBuffer.getVkFrameBuffer());

            commandBuffer.beginRecording();
            VkCommandBuffer cmdHandle = commandBuffer.getVkCommandBuffer();
            vkCmdBeginRenderPass(cmdHandle, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);

            vkCmdBindPipeline(cmdHandle, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLine.getVkPipeline());

            VkViewport.Buffer viewport = VkViewport.callocStack(1, stack)
                    .x(0)
                    .y(height)
                    .height(-height)
                    .width(width)
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            vkCmdSetViewport(cmdHandle, 0, viewport);

            VkRect2D.Buffer scissor = VkRect2D.callocStack(1, stack)
                    .extent(it -> it
                            .width(width)
                            .height(height))
                    .offset(it -> it
                            .x(0)
                            .y(0));
            vkCmdSetScissor(cmdHandle, 0, scissor);

            LongBuffer offsets = stack.mallocLong(1);
            offsets.put(0, 0L);
            ByteBuffer pushConstantBuffer = stack.malloc(GraphConstants.MAT4X4_SIZE);

            LongBuffer descriptorSets = stack.mallocLong(2)
                    .put(0, uniformsDescriptorSet.getVkDescriptorSet());
            for (VulkanMesh mesh : meshes) {
                LongBuffer vertexBuffer = stack.mallocLong(1);
                vertexBuffer.put(0, mesh.getVerticesBuffer().getBuffer());
                vkCmdBindVertexBuffers(cmdHandle, 0, vertexBuffer, offsets);
                vkCmdBindIndexBuffer(cmdHandle, mesh.getIndicesBuffer().getBuffer(), 0, VK_INDEX_TYPE_UINT32);

                TextureDescriptorSet textureDescriptorSet = descriptorSetMap.get(mesh.getTextureId());
                List<Entity> entities = scene.getEntitiesByMeshId(mesh.getId());
                for (Entity entity : entities) {
                    descriptorSets.put(1, textureDescriptorSet.getVkDescriptorSet());
                    vkCmdBindDescriptorSets(cmdHandle, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            pipeLine.getVkPipelineLayout(), 0, descriptorSets, null);

                    setPushConstants(cmdHandle, entity.getModelMatrix(), pushConstantBuffer);
                    vkCmdDrawIndexed(cmdHandle, mesh.getIndicesCount(), 1, 0, 0, 0);
                }
            }

            vkCmdEndRenderPass(cmdHandle);
            commandBuffer.endRecording();
        }
    }

    public void resize(SwapChain swapChain, Scene scene) {
        setProjectionUniform(scene.getPerspective().getPerspectiveMatrix());
        this.swapChain = swapChain;
        for (FrameBuffer frameBuffer : frameBuffers) {
            frameBuffer.cleanup();
        }
        for (ImageView imageView : depthImageViews) {
            imageView.cleanup();
        }
        for (Image image : depthImages) {
            image.cleanup();
        }
        createDepthImages();
        createFrameBuffers();
    }

    private void setProjectionUniform(Matrix4f projectionMatrix) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointerBuffer = stack.mallocPointer(1);
            vkCheck(vkMapMemory(device.getVkDevice(), projMatrixUniform.getMemory(), 0, projMatrixUniform.getAllocationSize(),
                    0, pointerBuffer), "Failed to map UBO memory");
            long data = pointerBuffer.get(0);
            ByteBuffer matrixBuffer = MemoryUtil.memByteBuffer(data, (int) projMatrixUniform.getAllocationSize());
            projectionMatrix.get(0, matrixBuffer);
            vkUnmapMemory(device.getVkDevice(), projMatrixUniform.getMemory());
        }
    }

    private void setPushConstants(VkCommandBuffer cmdHandle, Matrix4f modelMatrix, ByteBuffer pushConstantBuffer) {
        modelMatrix.get(0, pushConstantBuffer);
        vkCmdPushConstants(cmdHandle, pipeLine.getVkPipelineLayout(),
                VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstantBuffer);
    }

    public void submit(Queue queue) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int idx = swapChain.getCurrentFrame();
            CommandBuffer commandBuffer = commandBuffers[idx];
            Fence currentFence = fences[idx];
            SwapChain.SyncSemaphores syncSemaphores = swapChain.getSyncSemaphoresList()[idx];
            queue.submit(stack.pointers(commandBuffer.getVkCommandBuffer()),
                    stack.longs(syncSemaphores.imgAcquisitionSemaphores().getVkSemaphore()),
                    stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT),
                    stack.longs(syncSemaphores.renderCompleteSemaphores().getVkSemaphore()), currentFence);
        }
    }
}