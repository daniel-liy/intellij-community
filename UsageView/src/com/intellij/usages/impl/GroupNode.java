/*
 * Copyright 2000-2007 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.usages.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.Navigatable;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageGroup;
import com.intellij.usages.UsageView;
import com.intellij.usages.UsageViewSettings;
import com.intellij.usages.rules.MergeableUsage;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import java.util.*;

/**
 * @author max
 */
public class GroupNode extends Node implements Navigatable, Comparable<GroupNode> {
  private static final NodeComparator COMPARATOR = new NodeComparator();
  private final Object lock = new Object();
  private final UsageGroup myGroup;
  private final int myRuleIndex;
  private final Map<UsageGroup, GroupNode> mySubgroupNodes = new THashMap<UsageGroup, GroupNode>();
  private final List<UsageNode> myUsageNodes = new ArrayList<UsageNode>();
  private volatile int myRecursiveUsageCount = 0;

  public GroupNode(UsageGroup group, int ruleIndex, UsageViewTreeModelBuilder treeModel) {
    super(treeModel);
    setUserObject(group);
    myGroup = group;
    myRuleIndex = ruleIndex;
  }

  protected void updateNotify() {
    myGroup.update();
  }

  public String toString() {
    String result = "";
    if (myGroup != null) result += myGroup.getText(null);
    return children == null ? result : result + children.toString();
  }

  public GroupNode addGroup(@NotNull UsageGroup group, int ruleIndex) {
    synchronized (lock) {
      GroupNode node = mySubgroupNodes.get(group);
      if (node == null) {
        final GroupNode node1 = node = new GroupNode(group, ruleIndex, getBuilder());
        mySubgroupNodes.put(group, node);

        if (!getBuilder().isDetachedMode()) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              myTreeModel.insertNodeInto(node1, GroupNode.this, getNodeInsertionIndex(node1));
            }
          });
        }
      }
      return node;
    }
  }

  private UsageViewTreeModelBuilder getBuilder() {
    return (UsageViewTreeModelBuilder)myTreeModel;
  }

  public void removeAllChildren() {
    synchronized (lock) {
      ApplicationManager.getApplication().assertIsDispatchThread();
      super.removeAllChildren();
      mySubgroupNodes.clear();
      myRecursiveUsageCount = 0;
      myUsageNodes.clear();
    }
    myTreeModel.reload(this);
  }

  private UsageNode tryMerge(Usage usage) {
    if (!(usage instanceof MergeableUsage)) return null;
    if (!UsageViewSettings.getInstance().isFilterDuplicatedLine()) return null;
    for (UsageNode node : myUsageNodes) {
      Usage original = node.getUsage();
      if (original instanceof MergeableUsage) {
        if (((MergeableUsage)original).merge((MergeableUsage)usage)) return node;
      }
    }

    return null;
  }

  public boolean removeUsage(UsageNode usage) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    final Collection<GroupNode> groupNodes = mySubgroupNodes.values();
    for(Iterator<GroupNode> iterator = groupNodes.iterator();iterator.hasNext();) {
      final GroupNode groupNode = iterator.next();

      if(groupNode.removeUsage(usage)) {
        doUpdate();

        if (groupNode.getRecursiveUsageCount() == 0) {
          myTreeModel.removeNodeFromParent(groupNode);
          iterator.remove();
        }
        return true;
      }
    }

    if (myUsageNodes.remove(usage)) {
      doUpdate();
      return true;
    }

    return false;
  }

  private void doUpdate() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    --myRecursiveUsageCount;
    myTreeModel.nodeChanged(this);
  }

  public UsageNode addUsage(Usage usage) {
    final UsageNode node;
    synchronized (lock) {
      UsageNode mergedWith = tryMerge(usage);
      if (mergedWith != null) {
        return mergedWith;
      }
      node = new UsageNode(usage, getBuilder());
      myUsageNodes.add(node);
    }

    if (!getBuilder().isDetachedMode()) {
      SwingUtilities.invokeLater(new Runnable() {
        public void run() {
          myTreeModel.insertNodeInto(node, GroupNode.this, getNodeIndex(node));
          incrementUsageCount();
        }
      });
    }
    return node;
  }

  private int getNodeIndex(final UsageNode node) {
    int index = indexedBinarySearch(node);
    return index >= 0 ? index : -index-1;
  }

  private int indexedBinarySearch(UsageNode key) {
    int low = 0;
    int high = getChildCount() - 1;

    while (low <= high) {
      int mid = (low + high) / 2;
      TreeNode treeNode = getChildAt(mid);
      int cmp;
      if (treeNode instanceof UsageNode) {
        UsageNode midVal = (UsageNode)treeNode;
        cmp = midVal.compareTo(key);
      }
      else {
        cmp = -1;
      }
      if (cmp < 0) {
        low = mid + 1;
      }
      else if (cmp > 0) {
        high = mid - 1;
      }
      else {
        return mid; // key found
      }
    }
    return -(low + 1);  // key not found
  }


  private void incrementUsageCount() {
    GroupNode groupNode = this;
    while (true) {
      groupNode.myRecursiveUsageCount++;
      final GroupNode node = groupNode;
      myTreeModel.nodeChanged(node);
      TreeNode parent = groupNode.getParent();
      if (!(parent instanceof GroupNode)) return;
      groupNode = (GroupNode)parent;
    }
  }

  public String tree2string(int indent, String lineSeparator) {
    StringBuffer result = new StringBuffer();
    StringUtil.repeatSymbol(result, ' ', indent);

    if (myGroup != null) result.append(myGroup.toString());
    result.append("[");
    result.append(lineSeparator);

    Enumeration enumeration = children();
    while (enumeration.hasMoreElements()) {
      Node node = (Node)enumeration.nextElement();
      result.append(node.tree2string(indent + 4, lineSeparator));
      result.append(lineSeparator);
    }

    StringUtil.repeatSymbol(result, ' ', indent);
    result.append("]");
    result.append(lineSeparator);

    return result.toString();
  }

  protected boolean isDataValid() {
    return myGroup == null || myGroup.isValid();
  }

  protected boolean isDataReadOnly() {
    Enumeration enumeration = children();
    while (enumeration.hasMoreElements()) {
      Node node = (Node)enumeration.nextElement();
      if (node.isReadOnly()) return true;
    }
    return false;
  }

  int getNodeInsertionIndex(DefaultMutableTreeNode node) {
    Enumeration children = children();
    int idx = 0;
    while (children.hasMoreElements()) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode)children.nextElement();
      if (COMPARATOR.compare(child, node) >= 0) break;
      idx++;
    }
    return idx;
  }

  private static class NodeComparator implements Comparator<DefaultMutableTreeNode> {
    private static int getClassIndex(DefaultMutableTreeNode node) {
      if (node instanceof UsageNode) return 3;
      if (node instanceof GroupNode) return 2;
      if (node instanceof UsageTargetNode) return 1;
      return 0;
    }

    public int compare(DefaultMutableTreeNode n1, DefaultMutableTreeNode n2) {
      int classIdx1 = getClassIndex(n1);
      int classIdx2 = getClassIndex(n2);
      if (classIdx1 != classIdx2) return classIdx1 - classIdx2;
      if (classIdx1 == 2) return ((GroupNode)n1).compareTo((GroupNode)n2);

      return 0;
    }
  }

  public int compareTo(GroupNode groupNode) {
    if (myRuleIndex == groupNode.myRuleIndex) {
      return myGroup.compareTo(groupNode.myGroup);
    }

    return myRuleIndex - groupNode.myRuleIndex;
  }

  public UsageGroup getGroup() {
    return myGroup;
  }

  public int getRecursiveUsageCount() {
    return myRecursiveUsageCount;
  }

  public void navigate(boolean requestFocus) {
    if (myGroup != null) {
      myGroup.navigate(requestFocus);
    }
  }

  public boolean canNavigate() {
    return myGroup != null && myGroup.canNavigate();
  }

  public boolean canNavigateToSource() {
    return myGroup != null && myGroup.canNavigateToSource();
  }


  protected boolean isDataExcluded() {
    Enumeration enumeration = children();
    while (enumeration.hasMoreElements()) {
      Node node = (Node)enumeration.nextElement();
      if (!node.isExcluded()) return false;
    }
    return true;
  }

  protected String getText(UsageView view) {
    return myGroup.getText(view);
  }

  public Collection<GroupNode> getSubGroups() {
    return mySubgroupNodes.values();
  }
  public Collection<UsageNode> getUsageNodes() {
    return myUsageNodes;
  }
}
