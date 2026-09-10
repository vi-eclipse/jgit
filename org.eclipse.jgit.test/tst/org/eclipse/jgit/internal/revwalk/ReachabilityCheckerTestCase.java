/*
 * Copyright (C) 2019, Google LLC. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.internal.revwalk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.junit.LocalDiskRepositoryTestCase;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.ReachabilityChecker;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public abstract class ReachabilityCheckerTestCase
		extends LocalDiskRepositoryTestCase {

	protected abstract ReachabilityChecker createReachabilityChecker(
			RevWalk revWalk) throws Exception;

	private final boolean withCommitGraph;

	TestRepository<FileRepository> repo;

	@Parameters(name = "commitGraph: {0}")
	public static Object[] parameters() {
		return new Object[] { Boolean.FALSE, Boolean.TRUE };
	}

	public ReachabilityCheckerTestCase(boolean withCommitGraph) {
		this.withCommitGraph = withCommitGraph;
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		FileRepository db = createWorkRepository();
		repo = new TestRepository<>(db);
	}

	@Test
	public void reachable() throws Exception {
		RevCommit a = repo.commit().create();
		RevCommit b1 = repo.commit(a);
		RevCommit b2 = repo.commit(b1);
		RevCommit c1 = repo.commit(a);
		RevCommit c2 = repo.commit(c1);
		repo.update("refs/heads/checker", b2);
		setCommitGraph(repo.getRepository(), withCommitGraph);


		assertReachable("reachable from one tip", List.of(a),
				Stream.of(c2));
		assertReachable("reachable from another tip", List.of(a),
				Stream.of(b2));
		assertReachable("reachable from itself", List.of(a),
				Stream.of(a));
	}

	@Test
	public void reachable_merge() throws Exception {
		RevCommit a = repo.commit().create();
		RevCommit b1 = repo.commit(a);
		RevCommit b2 = repo.commit(b1);
		RevCommit c1 = repo.commit(a);
		RevCommit c2 = repo.commit(c1);
		RevCommit merge = repo.commit(c2, b2);
		repo.update("refs/heads/checker", merge);
		setCommitGraph(repo.getRepository(), withCommitGraph);

		assertReachable("reachable through one branch", List.of(b1),
				Stream.of(merge));
		assertReachable("reachable through another branch", List.of(c1),
				Stream.of(merge));
		assertReachable("reachable, before the branching", List.of(a),
				Stream.of(merge));
	}

	@Test
	public void unreachable_isLaterCommit() throws Exception {
		RevCommit a = repo.commit().create();
		RevCommit b1 = repo.commit(a);
		RevCommit b2 = repo.commit(b1);
		repo.update("refs/heads/checker", b2);
		setCommitGraph(repo.getRepository(), withCommitGraph);

		assertUnreachable("unreachable from the future", List.of(b2),
				Stream.of(b1));
	}

	@Test
	public void unreachable_differentBranch() throws Exception {
		RevCommit a = repo.commit().create();
		RevCommit b1 = repo.commit(a);
		RevCommit b2 = repo.commit(b1);
		RevCommit c1 = repo.commit(a);
		repo.update("refs/heads/checker", b2);
		setCommitGraph(repo.getRepository(), withCommitGraph);

		assertUnreachable("unreachable from different branch", List.of(c1),
				Stream.of(b2));
	}

	@Ignore
	@Test
	public void reachable_longChain() throws Exception {
		RevCommit root = repo.commit().create();
		ObjectId head = root;
		for (int i = 0; i < 10000; i++) {
			head = repo.unparsedCommit(head);
		}
		repo.update("refs/heads/master", head);
		setCommitGraph(repo.getRepository(), withCommitGraph);

		assertReachable("reachable with long chain in the middle",
				List.of(root), Stream.of(head));
	}

	/**
	 * Reproduces the performance regression described for the
	 * TopoSortPendingGenerator (introduced in
	 * ab51fe2bdb9d7f05287b686e75be7afc46d0c8f5): "the target is a recent
	 * commit in a short branch, other branches are older but much longer".
	 * <p>
	 * With the previous PendingGenerator/TopoSortGenerator pipeline, the walk
	 * quickly notices that everything still pending is UNINTERESTING (after
	 * a small over-scan buffer) and stops, without ever descending into the
	 * long unrelated branch. The generation-number based
	 * TopoSortPendingGenerator instead lets the (low) generation number of
	 * the short branch drag {@code minGeneration} down, forcing it to
	 * explore the whole long branch before it can decide anything, which
	 * turns an effectively O(1) check into an O(n) one.
	 */
	@Test
	public void unreachable_recentShortBranchWithOlderMuchLongerBranch()
			throws Exception {
		// Older, much longer, disjoint branch that will be used as
		// "uninteresting" starter.
		RevCommit longBranchRoot = repo.commit().create();
		ObjectId longBranchTip = longBranchRoot;
		for (int i = 0; i < 10_000; i++) {
			longBranchTip = repo.unparsedCommit(longBranchTip);
		}
		repo.update("refs/heads/long", longBranchTip);

		// Recent, short, disjoint branch whose tip is the target.
		RevCommit shortBranchRoot = repo.commit().create();
		ObjectId shortBranchTip = shortBranchRoot;
		for (int i = 0; i < 5; i++) {
			shortBranchTip = repo.unparsedCommit(shortBranchTip);
		}
		repo.update("refs/heads/short", shortBranchTip);

		setCommitGraph(repo.getRepository(), withCommitGraph);

		long start = System.nanoTime();
		assertUnreachable(
				"unrelated short, recent branch is not reachable "
						+ "from an older, much longer branch",
				List.of(shortBranchTip), Stream.of(longBranchTip));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		throw new AssertionError("reachability check took " + elapsed);
	}

	private static void setCommitGraph(FileRepository repository,
			boolean enabled) throws Exception {
		StoredConfig config = repository.getConfig();

		config.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH, enabled);
		config.setBoolean(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_WRITE_COMMIT_GRAPH, enabled);
		config.setBoolean(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_WRITE_CHANGED_PATHS, enabled);

		if (enabled) {
			new GC(repository).gc().get();
		}

	}

	private Optional<RevCommit> areAllReachable(Collection<AnyObjectId> targets,
			Stream<AnyObjectId> starters) throws Exception {
		RevWalk walk = new RevWalk(repo.getRevWalk().getObjectReader());
		ReachabilityChecker checker = createReachabilityChecker(walk);

		return checker.areAllReachable(
				targets.stream().map(walk::lookupCommit).toList(),
				starters.map(walk::lookupCommit).toList().stream());
	}

	private void assertReachable(String msg, Collection<AnyObjectId> targets,
			Stream<AnyObjectId> starters) throws Exception {
		assertReachable(msg, areAllReachable(targets, starters));
	}

	private void assertUnreachable(String msg, Collection<AnyObjectId> targets,
			Stream<AnyObjectId> starters) throws Exception {
		assertUnreachable(msg, areAllReachable(targets, starters));
	}

	private static void assertReachable(String msg,
			Optional<RevCommit> result) {
		assertFalse(msg, result.isPresent());
	}

	private static void assertUnreachable(String msg,
			Optional<RevCommit> result) {
		assertTrue(msg, result.isPresent());
	}
}
