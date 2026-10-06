package com.projectardor.interview.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * LeetCode Hot 100 (top-100-liked) catalog used for mock-interview coding questions.
 * Slugs are identical on leetcode.com and leetcode.cn, so the frontend only swaps the host.
 */
@Component
public class LeetCodeHot100 {

    public record Problem(int id, String slug, String titleEn, String titleZh) {}

    public static final List<Problem> PROBLEMS = List.of(
            new Problem(1, "two-sum", "Two Sum", "两数之和"),
            new Problem(49, "group-anagrams", "Group Anagrams", "字母异位词分组"),
            new Problem(128, "longest-consecutive-sequence", "Longest Consecutive Sequence", "最长连续序列"),
            new Problem(283, "move-zeroes", "Move Zeroes", "移动零"),
            new Problem(11, "container-with-most-water", "Container With Most Water", "盛最多水的容器"),
            new Problem(15, "3sum", "3Sum", "三数之和"),
            new Problem(42, "trapping-rain-water", "Trapping Rain Water", "接雨水"),
            new Problem(3, "longest-substring-without-repeating-characters", "Longest Substring Without Repeating Characters", "无重复字符的最长子串"),
            new Problem(438, "find-all-anagrams-in-a-string", "Find All Anagrams in a String", "找到字符串中所有字母异位词"),
            new Problem(560, "subarray-sum-equals-k", "Subarray Sum Equals K", "和为 K 的子数组"),
            new Problem(239, "sliding-window-maximum", "Sliding Window Maximum", "滑动窗口最大值"),
            new Problem(76, "minimum-window-substring", "Minimum Window Substring", "最小覆盖子串"),
            new Problem(53, "maximum-subarray", "Maximum Subarray", "最大子数组和"),
            new Problem(56, "merge-intervals", "Merge Intervals", "合并区间"),
            new Problem(189, "rotate-array", "Rotate Array", "轮转数组"),
            new Problem(238, "product-of-array-except-self", "Product of Array Except Self", "除了自身以外数组的乘积"),
            new Problem(41, "first-missing-positive", "First Missing Positive", "缺失的第一个正数"),
            new Problem(73, "set-matrix-zeroes", "Set Matrix Zeroes", "矩阵置零"),
            new Problem(54, "spiral-matrix", "Spiral Matrix", "螺旋矩阵"),
            new Problem(48, "rotate-image", "Rotate Image", "旋转图像"),
            new Problem(240, "search-a-2d-matrix-ii", "Search a 2D Matrix II", "搜索二维矩阵 II"),
            new Problem(160, "intersection-of-two-linked-lists", "Intersection of Two Linked Lists", "相交链表"),
            new Problem(206, "reverse-linked-list", "Reverse Linked List", "反转链表"),
            new Problem(234, "palindrome-linked-list", "Palindrome Linked List", "回文链表"),
            new Problem(141, "linked-list-cycle", "Linked List Cycle", "环形链表"),
            new Problem(142, "linked-list-cycle-ii", "Linked List Cycle II", "环形链表 II"),
            new Problem(21, "merge-two-sorted-lists", "Merge Two Sorted Lists", "合并两个有序链表"),
            new Problem(2, "add-two-numbers", "Add Two Numbers", "两数相加"),
            new Problem(19, "remove-nth-node-from-end-of-list", "Remove Nth Node From End of List", "删除链表的倒数第 N 个结点"),
            new Problem(24, "swap-nodes-in-pairs", "Swap Nodes in Pairs", "两两交换链表中的节点"),
            new Problem(25, "reverse-nodes-in-k-group", "Reverse Nodes in k-Group", "K 个一组翻转链表"),
            new Problem(138, "copy-list-with-random-pointer", "Copy List with Random Pointer", "随机链表的复制"),
            new Problem(148, "sort-list", "Sort List", "排序链表"),
            new Problem(23, "merge-k-sorted-lists", "Merge k Sorted Lists", "合并 K 个升序链表"),
            new Problem(146, "lru-cache", "LRU Cache", "LRU 缓存"),
            new Problem(94, "binary-tree-inorder-traversal", "Binary Tree Inorder Traversal", "二叉树的中序遍历"),
            new Problem(104, "maximum-depth-of-binary-tree", "Maximum Depth of Binary Tree", "二叉树的最大深度"),
            new Problem(226, "invert-binary-tree", "Invert Binary Tree", "翻转二叉树"),
            new Problem(101, "symmetric-tree", "Symmetric Tree", "对称二叉树"),
            new Problem(543, "diameter-of-binary-tree", "Diameter of Binary Tree", "二叉树的直径"),
            new Problem(102, "binary-tree-level-order-traversal", "Binary Tree Level Order Traversal", "二叉树的层序遍历"),
            new Problem(108, "convert-sorted-array-to-binary-search-tree", "Convert Sorted Array to Binary Search Tree", "将有序数组转换为二叉搜索树"),
            new Problem(98, "validate-binary-search-tree", "Validate Binary Search Tree", "验证二叉搜索树"),
            new Problem(230, "kth-smallest-element-in-a-bst", "Kth Smallest Element in a BST", "二叉搜索树中第 K 小的元素"),
            new Problem(199, "binary-tree-right-side-view", "Binary Tree Right Side View", "二叉树的右视图"),
            new Problem(114, "flatten-binary-tree-to-linked-list", "Flatten Binary Tree to Linked List", "二叉树展开为链表"),
            new Problem(105, "construct-binary-tree-from-preorder-and-inorder-traversal", "Construct Binary Tree from Preorder and Inorder Traversal", "从前序与中序遍历序列构造二叉树"),
            new Problem(437, "path-sum-iii", "Path Sum III", "路径总和 III"),
            new Problem(236, "lowest-common-ancestor-of-a-binary-tree", "Lowest Common Ancestor of a Binary Tree", "二叉树的最近公共祖先"),
            new Problem(124, "binary-tree-maximum-path-sum", "Binary Tree Maximum Path Sum", "二叉树中的最大路径和"),
            new Problem(200, "number-of-islands", "Number of Islands", "岛屿数量"),
            new Problem(994, "rotting-oranges", "Rotting Oranges", "腐烂的橘子"),
            new Problem(207, "course-schedule", "Course Schedule", "课程表"),
            new Problem(208, "implement-trie-prefix-tree", "Implement Trie (Prefix Tree)", "实现 Trie (前缀树)"),
            new Problem(46, "permutations", "Permutations", "全排列"),
            new Problem(78, "subsets", "Subsets", "子集"),
            new Problem(17, "letter-combinations-of-a-phone-number", "Letter Combinations of a Phone Number", "电话号码的字母组合"),
            new Problem(39, "combination-sum", "Combination Sum", "组合总和"),
            new Problem(22, "generate-parentheses", "Generate Parentheses", "括号生成"),
            new Problem(79, "word-search", "Word Search", "单词搜索"),
            new Problem(131, "palindrome-partitioning", "Palindrome Partitioning", "分割回文串"),
            new Problem(51, "n-queens", "N-Queens", "N 皇后"),
            new Problem(35, "search-insert-position", "Search Insert Position", "搜索插入位置"),
            new Problem(74, "search-a-2d-matrix", "Search a 2D Matrix", "搜索二维矩阵"),
            new Problem(34, "find-first-and-last-position-of-element-in-sorted-array", "Find First and Last Position of Element in Sorted Array", "在排序数组中查找元素的第一个和最后一个位置"),
            new Problem(33, "search-in-rotated-sorted-array", "Search in Rotated Sorted Array", "搜索旋转排序数组"),
            new Problem(153, "find-minimum-in-rotated-sorted-array", "Find Minimum in Rotated Sorted Array", "寻找旋转排序数组中的最小值"),
            new Problem(4, "median-of-two-sorted-arrays", "Median of Two Sorted Arrays", "寻找两个正序数组的中位数"),
            new Problem(20, "valid-parentheses", "Valid Parentheses", "有效的括号"),
            new Problem(155, "min-stack", "Min Stack", "最小栈"),
            new Problem(394, "decode-string", "Decode String", "字符串解码"),
            new Problem(739, "daily-temperatures", "Daily Temperatures", "每日温度"),
            new Problem(84, "largest-rectangle-in-histogram", "Largest Rectangle in Histogram", "柱状图中最大的矩形"),
            new Problem(215, "kth-largest-element-in-an-array", "Kth Largest Element in an Array", "数组中的第K个最大元素"),
            new Problem(347, "top-k-frequent-elements", "Top K Frequent Elements", "前 K 个高频元素"),
            new Problem(295, "find-median-from-data-stream", "Find Median from Data Stream", "数据流的中位数"),
            new Problem(121, "best-time-to-buy-and-sell-stock", "Best Time to Buy and Sell Stock", "买卖股票的最佳时机"),
            new Problem(55, "jump-game", "Jump Game", "跳跃游戏"),
            new Problem(45, "jump-game-ii", "Jump Game II", "跳跃游戏 II"),
            new Problem(763, "partition-labels", "Partition Labels", "划分字母区间"),
            new Problem(70, "climbing-stairs", "Climbing Stairs", "爬楼梯"),
            new Problem(118, "pascals-triangle", "Pascal's Triangle", "杨辉三角"),
            new Problem(198, "house-robber", "House Robber", "打家劫舍"),
            new Problem(279, "perfect-squares", "Perfect Squares", "完全平方数"),
            new Problem(322, "coin-change", "Coin Change", "零钱兑换"),
            new Problem(139, "word-break", "Word Break", "单词拆分"),
            new Problem(300, "longest-increasing-subsequence", "Longest Increasing Subsequence", "最长递增子序列"),
            new Problem(152, "maximum-product-subarray", "Maximum Product Subarray", "乘积最大子数组"),
            new Problem(416, "partition-equal-subset-sum", "Partition Equal Subset Sum", "分割等和子集"),
            new Problem(32, "longest-valid-parentheses", "Longest Valid Parentheses", "最长有效括号"),
            new Problem(62, "unique-paths", "Unique Paths", "不同路径"),
            new Problem(64, "minimum-path-sum", "Minimum Path Sum", "最小路径和"),
            new Problem(5, "longest-palindromic-substring", "Longest Palindromic Substring", "最长回文子串"),
            new Problem(1143, "longest-common-subsequence", "Longest Common Subsequence", "最长公共子序列"),
            new Problem(72, "edit-distance", "Edit Distance", "编辑距离"),
            new Problem(136, "single-number", "Single Number", "只出现一次的数字"),
            new Problem(169, "majority-element", "Majority Element", "多数元素"),
            new Problem(75, "sort-colors", "Sort Colors", "颜色分类"),
            new Problem(31, "next-permutation", "Next Permutation", "下一个排列"),
            new Problem(287, "find-the-duplicate-number", "Find the Duplicate Number", "寻找重复数"));

    private static final Map<String, Problem> BY_SLUG = PROBLEMS.stream()
            .collect(Collectors.toUnmodifiableMap(Problem::slug, Function.identity()));

    public static Optional<Problem> find(String slug) {
        return slug == null ? Optional.empty() : Optional.ofNullable(BY_SLUG.get(slug));
    }

    public Problem random() {
        return PROBLEMS.get(ThreadLocalRandom.current().nextInt(PROBLEMS.size()));
    }
}
