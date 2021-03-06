/*******************************************************************************
 * Copyright (C) 2011, 2013 Bernard Leach <leachbj@bouncycastle.org> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.staging;

import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.ADDED;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.CHANGED;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.CONFLICTING;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.MISSING;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.MISSING_AND_CHANGED;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.MODIFIED;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.PARTIALLY_MODIFIED;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.REMOVED;
import static org.eclipse.egit.ui.internal.staging.StagingEntry.State.UNTRACKED;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.staging.StagingView.Presentation;
import org.eclipse.egit.ui.internal.staging.StagingView.StagingViewUpdate;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.ui.model.WorkbenchContentProvider;

/**
 * ContentProvider for staged and unstaged tree nodes
 */
public class StagingViewContentProvider extends WorkbenchContentProvider {
	/** All files for the section (staged or unstaged). */
	private StagingEntry[] content = new StagingEntry[0];

	/** Root nodes for the "Tree" presentation. */
	private Object[] treeRoots;

	/** Root nodes for the "Compact Tree" presentation. */
	private Object[] compactTreeRoots;

	private StagingView stagingView;
	private boolean unstagedSection;

	private Repository repository;

	StagingViewContentProvider(StagingView stagingView, boolean unstagedSection) {
		this.stagingView = stagingView;
		this.unstagedSection = unstagedSection;
	}

	public Object getParent(Object element) {
		if (element instanceof StagingFolderEntry)
			return ((StagingFolderEntry) element).getParent();
		if (element instanceof StagingEntry)
			return ((StagingEntry) element).getParent();
		return null;
	}

	public boolean hasChildren(Object element) {
		return !(element instanceof StagingEntry);
	}

	public Object[] getElements(Object inputElement) {
		return getChildren(inputElement);
	}

	public Object[] getChildren(Object parentElement) {
		if (repository == null)
			return new Object[0];
		if (parentElement instanceof StagingEntry)
			return new Object[0];
		if (parentElement instanceof StagingFolderEntry) {
			return ((StagingFolderEntry) parentElement).getChildren();
		} else {
			// Return the root nodes
			if (stagingView.getPresentation() == Presentation.LIST)
				return content;
			else
				return getTreePresentationRoots();
		}
	}

	Object[] getTreePresentationRoots() {
		Presentation presentation = stagingView.getPresentation();
		switch (presentation) {
		case COMPACT_TREE:
			return getCompactTreeRoots();
		case TREE:
			return getTreeRoots();
		default:
			return new StagingFolderEntry[0];
		}
	}

	private Object[] getCompactTreeRoots() {
		if (compactTreeRoots == null)
			compactTreeRoots = calculateTreePresentationRoots(true);
		return compactTreeRoots;
	}

	private Object[] getTreeRoots() {
		if (treeRoots == null)
			treeRoots = calculateTreePresentationRoots(false);
		return treeRoots;
	}

	private Object[] calculateTreePresentationRoots(boolean compact) {
		if (content == null || content.length == 0)
			return new Object[0];

		List<Object> roots = new ArrayList<Object>();
		Map<IPath, List<Object>> childrenForPath = new HashMap<IPath, List<Object>>();

		Set<IPath> folderPaths = new HashSet<IPath>();
		Map<IPath, String> childSegments = new HashMap<IPath, String>();

		for (StagingEntry file : content) {
			IPath folderPath = file.getParentPath();
			if (folderPath.segmentCount() == 0) {
				// No folders need to be created, this is a root file
				roots.add(file);
				continue;
			}
			folderPaths.add(folderPath);
			addChild(childrenForPath, folderPath, file);
			for (IPath p = folderPath; p.segmentCount() != 1; p = p
					.removeLastSegments(1)) {
				IPath parent = p.removeLastSegments(1);
				if (!compact) {
					folderPaths.add(parent);
				} else {
					String childSegment = p.lastSegment();
					String knownChildSegment = childSegments.get(parent);
					if (knownChildSegment == null) {
						childSegments.put(parent, childSegment);
					} else if (!childSegment.equals(knownChildSegment)) {
						// The parent has more than 1 direct child folder -> we
						// need to make a node for it.
						folderPaths.add(parent);
					}
				}
			}
		}

		IPath workingDirectory = new Path(repository.getWorkTree()
				.getAbsolutePath());

		List<StagingFolderEntry> folderEntries = new ArrayList<StagingFolderEntry>();
		for (IPath folderPath : folderPaths) {
			IPath parent = folderPath.removeLastSegments(1);
			// Find first existing parent node, but stop at root
			while (parent.segmentCount() != 0 && !folderPaths.contains(parent))
				parent = parent.removeLastSegments(1);
			if (parent.segmentCount() == 0) {
				// Parent is root
				StagingFolderEntry folderEntry = new StagingFolderEntry(
						workingDirectory, folderPath, folderPath);
				folderEntries.add(folderEntry);
				roots.add(folderEntry);
			} else {
				// Parent is existing node
				IPath nodePath = folderPath.makeRelativeTo(parent);
				StagingFolderEntry folderEntry = new StagingFolderEntry(
						workingDirectory, folderPath, nodePath);
				folderEntries.add(folderEntry);
				addChild(childrenForPath, parent, folderEntry);
			}
		}

		for (StagingFolderEntry folderEntry : folderEntries) {
			List<Object> children = childrenForPath.get(folderEntry.getPath());
			if (children != null) {
				for (Object child : children) {
					if (child instanceof StagingEntry)
						((StagingEntry) child).setParent(folderEntry);
					else if (child instanceof StagingFolderEntry)
						((StagingFolderEntry) child).setParent(folderEntry);
				}
				Collections.sort(children, EntryComparator.INSTANCE);
				folderEntry.setChildren(children.toArray());
			}
		}

		Collections.sort(roots, EntryComparator.INSTANCE);
		return roots.toArray();
	}

	private static void addChild(Map<IPath, List<Object>> childrenForPath,
			IPath path, Object child) {
		List<Object> children = childrenForPath.get(path);
		if (children == null) {
			children = new ArrayList<Object>();
			childrenForPath.put(path, children);
		}
		children.add(child);
	}

	int getShownCount() {
		String filterString = getFilterString();
		if (filterString.length() == 0) {
			return getCount();
		} else {
			int shownCount = 0;
			for (StagingEntry entry : content) {
				if (isInFilter(entry))
					shownCount++;
			}
			return shownCount;
		}
	}

	List<StagingEntry> getStagingEntriesFiltered(StagingFolderEntry folder) {
		List<StagingEntry> stagingEntries = new ArrayList<StagingEntry>();
		for (StagingEntry stagingEntry : content) {
			if (folder.getLocation().isPrefixOf(stagingEntry.getLocation())) {
				if (isInFilter(stagingEntry))
					stagingEntries.add(stagingEntry);
			}
		}
		return stagingEntries;
	}

	boolean isInFilter(StagingEntry stagingEntry) {
		String filterString = getFilterString();
		return filterString.length() == 0
				|| stagingEntry.getPath().toUpperCase()
						.contains(filterString.toUpperCase());
	}

	private String getFilterString() {
		return stagingView.getFilterString();
	}

	boolean hasVisibleChildren(StagingFolderEntry folder) {
		if (getFilterString().length() == 0)
			return true;
		else
			return !getStagingEntriesFiltered(folder).isEmpty();
	}

	StagingEntry[] getStagingEntries() {
		return content;
	}

	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		if (!(newInput instanceof StagingViewUpdate))
			return;

		StagingViewUpdate update = (StagingViewUpdate) newInput;

		if (update.repository == null || update.indexDiff == null) {
			content = new StagingEntry[0];
			treeRoots = new Object[0];
			compactTreeRoots = new Object[0];
			return;
		}

		if (update.repository != repository) {
			treeRoots = null;
			compactTreeRoots = null;
		}

		repository = update.repository;

		Set<StagingEntry> nodes = new TreeSet<StagingEntry>(
				new Comparator<StagingEntry>() {
					public int compare(StagingEntry o1, StagingEntry o2) {
						return o1.getPath().compareTo(o2.getPath());
					}
				});

		if (update.changedResources != null
				&& !update.changedResources.isEmpty()) {
			nodes.addAll(Arrays.asList(content));
			for (String res : update.changedResources)
				for (StagingEntry entry : content)
					if (entry.getPath().equals(res))
						nodes.remove(entry);
		}

		final IndexDiffData indexDiff = update.indexDiff;
		if (unstagedSection) {
			for (String file : indexDiff.getMissing())
				if (indexDiff.getChanged().contains(file))
					nodes.add(new StagingEntry(repository, MISSING_AND_CHANGED,
							file));
				else
					nodes.add(new StagingEntry(repository, MISSING, file));
			for (String file : indexDiff.getModified())
				if (indexDiff.getChanged().contains(file))
					nodes.add(new StagingEntry(repository, PARTIALLY_MODIFIED,
							file));
				else
					nodes.add(new StagingEntry(repository, MODIFIED, file));
			for (String file : indexDiff.getUntracked())
				nodes.add(new StagingEntry(repository, UNTRACKED, file));
			for (String file : indexDiff.getConflicting())
				nodes.add(new StagingEntry(repository, CONFLICTING, file));
		} else {
			for (String file : indexDiff.getAdded())
				nodes.add(new StagingEntry(repository, ADDED, file));
			for (String file : indexDiff.getChanged())
				nodes.add(new StagingEntry(repository, CHANGED, file));
			for (String file : indexDiff.getRemoved())
				nodes.add(new StagingEntry(repository, REMOVED, file));
		}

		try {
		SubmoduleWalk walk = SubmoduleWalk.forIndex(repository);
		while(walk.next())
			for (StagingEntry entry : nodes)
				entry.setSubmodule(entry.getPath().equals(walk.getPath()));
		} catch(IOException e) {
			Activator.error(UIText.StagingViewContentProvider_SubmoduleError, e);
		}

		content = nodes.toArray(new StagingEntry[nodes.size()]);

		treeRoots = null;
		compactTreeRoots = null;
	}

	public void dispose() {
		// nothing to dispose
	}

	/**
	 * @return StagingEntry count
	 */
	public int getCount() {
		if (content == null)
			return 0;
		else
			return content.length;
	}

	private static class EntryComparator implements Comparator<Object> {
		public static EntryComparator INSTANCE = new EntryComparator();

		public int compare(Object o1, Object o2) {
			if (o1 instanceof StagingEntry) {
				if (o2 instanceof StagingEntry) {
					StagingEntry e1 = (StagingEntry) o1;
					StagingEntry e2 = (StagingEntry) o2;
					return e1.getPath().compareTo(e2.getPath());
				} else {
					// Files should come after folders
					return 1;
				}
			} else if (o1 instanceof StagingFolderEntry) {
				if (o2 instanceof StagingFolderEntry) {
					StagingFolderEntry f1 = (StagingFolderEntry) o1;
					StagingFolderEntry f2 = (StagingFolderEntry) o2;
					return f1.getPath().toString()
							.compareTo(f2.getPath().toString());
				} else {
					// Folders should come before files
					return -1;
				}
			} else {
				return 0;
			}
		}
	}

}