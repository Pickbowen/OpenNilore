package shit.nilore.modules.impl.misc.ai.path;

public class BinaryHeapOpenSet {
    private PathNode[] heap = new PathNode[4096];
    private int size = 0;

    public boolean isEmpty() { return size == 0; }
    public int size() { return size; }

    public void insert(PathNode node) {
        if (size == heap.length) {
            PathNode[] newHeap = new PathNode[heap.length * 2];
            System.arraycopy(heap, 0, newHeap, 0, heap.length);
            heap = newHeap;
        }
        heap[size] = node;
        node.heapPosition = size;
        size++;
        siftUp(size - 1);
    }

    public PathNode removeLowest() {
        PathNode result = heap[0];
        size--;
        if (size > 0) {
            heap[0] = heap[size];
            heap[0].heapPosition = 0;
            heap[size] = null;
            siftDown(0);
        } else {
            heap[0] = null;
        }
        result.heapPosition = -1;
        return result;
    }

    public void update(PathNode node) {
        if (node.heapPosition >= 0) {
            siftUp(node.heapPosition);
        }
    }

    private void siftUp(int pos) {
        while (pos > 0) {
            int parent = (pos - 1) / 2;
            if (heap[pos].combinedCost < heap[parent].combinedCost) {
                swap(pos, parent);
                pos = parent;
            } else break;
        }
    }

    private void siftDown(int pos) {
        while (true) {
            int smallest = pos;
            int left = 2 * pos + 1;
            int right = 2 * pos + 2;
            if (left < size && heap[left].combinedCost < heap[smallest].combinedCost) smallest = left;
            if (right < size && heap[right].combinedCost < heap[smallest].combinedCost) smallest = right;
            if (smallest != pos) { swap(pos, smallest); pos = smallest; }
            else break;
        }
    }

    private void swap(int a, int b) {
        PathNode tmp = heap[a];
        heap[a] = heap[b];
        heap[b] = tmp;
        heap[a].heapPosition = a;
        heap[b].heapPosition = b;
    }
}
