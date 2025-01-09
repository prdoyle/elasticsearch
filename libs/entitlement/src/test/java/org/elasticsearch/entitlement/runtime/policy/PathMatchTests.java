/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.policy;

import org.elasticsearch.test.ESTestCase;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class PathMatchTests extends ESTestCase {

    enum Operation {
        READ,
        WRITE
    };

    record FilePermission(String path, Operation operation) {
        public boolean isRead() {
            return true;
        }

        public boolean isWrite() {
            return operation == Operation.WRITE;
        }
    }

    static class PathMatcher {
        final String[] readPaths, writePaths;

        PathMatcher(Collection<FilePermission> permissions) {
            readPaths = permissions.stream().filter(FilePermission::isRead).map(FilePermission::path).sorted().toArray(String[]::new);
            writePaths = permissions.stream().filter(FilePermission::isWrite).map(FilePermission::path).sorted().toArray(String[]::new);
        }

        public boolean checkRead(String path) {
            return check(path, readPaths);
        }

        public boolean checkWrite(String path) {
            return check(path, writePaths);
        }

        private boolean check(String needle, String[] haystack) {
            int candidate;
            int searchResult = Arrays.binarySearch(haystack, needle);
            if (searchResult >= 0) {
                // Exact match
                candidate = searchResult;
            } else {
                int insertionPoint = -(searchResult + 1);
                // The longest prefix is the item that would precede needle if needle were inserted in the list.
                candidate = insertionPoint - 1;
            }
            if (candidate < 0) {
                // This happens if all haystack entries come after needle lexicographically, so none can be a prefix.
                return false;
            } else {
                return needle.startsWith(haystack[candidate]);
            }
        }

    }

    public void testPathMatcher() {
        PathMatcher pathMatcher = new PathMatcher(
            List.of(
                new FilePermission("/etc/test", Operation.READ),
                new FilePermission("/tmp", Operation.WRITE),
                new FilePermission("/etc/test/test.conf", Operation.WRITE)
            )
        );

        check("/", false, false, pathMatcher);
        check("/etc", false, false, pathMatcher);
        check("/etc/passed", false, false, pathMatcher);
        check("/etc/test", true, false, pathMatcher);
        check("/etc/test/some.file", true, false, pathMatcher);
        check("/etc/test/subdir/some.file", true, false, pathMatcher);
        check("/etc/test/test.conf", true, true, pathMatcher);
        check("/tmp", true, true, pathMatcher);
        check("/tmp/some.file", true, true, pathMatcher);
    }

    void check(String needle, boolean canRead, boolean canWrite, PathMatcher pathMatcher) {
        assertEquals("Read permission for: " + needle, canRead, pathMatcher.checkRead(needle));
        assertEquals("Write permission for: " + needle, canWrite, pathMatcher.checkWrite(needle));
    }
}
