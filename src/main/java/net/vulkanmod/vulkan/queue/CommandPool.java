package net.vulkanmod.vulkan.queue;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.lwjgl.vulkan.VK10.*;

public class CommandPool {

    private long id;
    private final List<CommandBuffer> commandBuffers = new ObjectArrayList<>();
    private final Queue<CommandBuffer> availableCmdBuffers = new ArrayDeque<>();

    public CommandPool(int queueFamilyIndex) {
        createCommandPool(queueFamilyIndex);
    }

    private void createCommandPool(int queueFamily) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);
            if (vkCreateCommandPool(Vulkan.getVkDevice(), poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }
            this.id = pCommandPool.get(0);
        }
    }

    public CommandBuffer getCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (availableCmdBuffers.isEmpty()) {
                allocateCommandBuffers(stack);
            }
            return availableCmdBuffers.poll();
        }
    }

    private void allocateCommandBuffers(MemoryStack stack) {
        final int size = 10;

        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
        allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        allocInfo.commandPool(id);
        allocInfo.commandBufferCount(size);

        PointerBuffer pCommandBuffer = stack.mallocPointer(size);
        if (vkAllocateCommandBuffers(Vulkan.getVkDevice(), allocInfo, pCommandBuffer) != VK_SUCCESS) {
            throw new RuntimeException("Failed to allocate command buffers");
        }

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
        fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

        VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
        semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

        for (int i = 0; i < size; i++) {
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(Vulkan.getVkDevice(), fenceInfo, null, pFence);

            LongBuffer pSemaphore = stack.mallocLong(1);
            vkCreateSemaphore(Vulkan.getVkDevice(), semaphoreInfo, null, pSemaphore);

            VkCommandBuffer vkCmdBuffer = new VkCommandBuffer(pCommandBuffer.get(i), Vulkan.getVkDevice());
            CommandBuffer cmdBuffer = new CommandBuffer(this, vkCmdBuffer, pFence.get(0), pSemaphore.get(0));
            commandBuffers.add(cmdBuffer);
            availableCmdBuffers.add(cmdBuffer);
        }
    }

    public void addToAvailable(CommandBuffer commandBuffer) {
        availableCmdBuffers.add(commandBuffer);
    }

    public void cleanUp() {
        VkDevice device = Vulkan.getVkDevice();
        for (CommandBuffer cmd : commandBuffers) {
            vkDestroyFence(device, cmd.fence, null);
            vkDestroySemaphore(device, cmd.semaphore, null);
        }
        vkResetCommandPool(device, id, VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT);
        vkDestroyCommandPool(device, id, null);
    }

    public long getId() {
        return id;
    }

    public static class CommandBuffer {

        private final CommandPool commandPool;
        private final VkCommandBuffer handle;
        private final long fence;
        private final long semaphore;

        private boolean submitted;
        private boolean recording;

        public CommandBuffer(CommandPool commandPool, VkCommandBuffer handle, long fence, long semaphore) {
            this.commandPool = commandPool;
            this.handle = handle;
            this.fence = fence;
            this.semaphore = semaphore;
        }

        public void begin(MemoryStack stack) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(handle, beginInfo);
            recording = true;
        }

        public long submit(MemoryStack stack, VkQueue queue, boolean useSemaphore) {
            VkDevice device = Vulkan.getVkDevice();

            vkEndCommandBuffer(handle);
            vkResetFences(device, fence);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(handle));
            if (useSemaphore) {
                submitInfo.pSignalSemaphores(stack.longs(semaphore));
            }

            if (vkQueueSubmit(queue, submitInfo, fence) != VK_SUCCESS) {
                throw new RuntimeException("Failed to submit command buffer");
            }

            recording = false;
            submitted = true;
            return fence;
        }

        public void reset() {
            VkDevice device = Vulkan.getVkDevice();
            vkWaitForFences(device, fence, true, Long.MAX_VALUE);
            vkResetFences(device, fence);
            vkResetCommandBuffer(handle, 0);
            submitted = false;
            recording = false;
            commandPool.addToAvailable(this);
        }

        public VkCommandBuffer getHandle() { return handle; }
        public long getFence() { return fence; }
        public long getSemaphore() { return semaphore; }
        public boolean isSubmitted() { return submitted; }
        public boolean isRecording() { return recording; }
    }
        }
