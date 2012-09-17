/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Tom Huybrechts
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.model;

import jenkins.model.lazy.AbstractLazyLoadRunMap;
import org.apache.commons.collections.comparators.ReverseComparator;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static jenkins.model.lazy.AbstractLazyLoadRunMap.Direction.*;

/**
 * {@link Map} from build number to {@link Run}.
 *
 * <p>
 * This class is multi-thread safe by using copy-on-write technique,
 * and it also updates the bi-directional links within {@link Run}
 * accordingly.
 *
 * @author Kohsuke Kawaguchi
 */
public final class RunMap<R extends Run<?,R>> extends AbstractLazyLoadRunMap<R> {
    /**
     * Read-only view of this map.
     */
    private final SortedMap<Integer,R> view = Collections.unmodifiableSortedMap(this);

    private Constructor<R> cons;

    // TODO: before first complete build
    // patch up next/previous build link


    /**
     * @deprecated
     *      Use {@link #RunMap(File, Constructor)}.
     */
    public RunMap() {
        super(null); // will be set later
    }

    /**
     * @param cons
     *      Used to create new instance of {@link Run}.
     */
    public RunMap(File baseDir, Constructor cons) {
        super(baseDir);
        this.cons = cons;
    }

    public boolean remove(R run) {
        return removeValue(run);
    }

    @Override
    public boolean removeValue(R run) {
        if(run.nextBuild!=null)
            run.nextBuild.previousBuild = run.previousBuild;
        if(run.previousBuild!=null)
            run.previousBuild.nextBuild = run.nextBuild;

        return super.removeValue(run);
    }

    /**
     * Gets the read-only view of this map.
     */
    public SortedMap<Integer,R> getView() {
        return view;
    }

    /**
     * This is the newest build (with the biggest build number)
     */
    public R newestValue() {
        return search(Integer.MAX_VALUE, DESC);
    }

    /**
     * This is the oldest build (with the smallest build number)
     */
    public R oldestValue() {
        return search(Integer.MIN_VALUE, ASC);
    }

    /**
     * @deprecated
     *      Use {@link ReverseComparator}
     */
    public static final Comparator<Comparable> COMPARATOR = new Comparator<Comparable>() {
        public int compare(Comparable o1, Comparable o2) {
            return -o1.compareTo(o2);
        }
    };

    /**
     * {@link Run} factory.
     */
    public interface Constructor<R extends Run<?,R>> {
        R create(File dir) throws IOException;
    }

    @Override
    protected final int getNumberOf(R r) {
        return r.getNumber();
    }

    @Override
    protected final String getIdOf(R r) {
        return r.getId();
    }

    @Override
    protected FilenameFilter createDirectoryFilter() {
        final SimpleDateFormat formatter = Run.ID_FORMATTER.get();

        return new FilenameFilter() {
            public boolean accept(File dir, String name) {
                // JENKINS-1461 sometimes create bogus data directories with impossible dates, such as year 0, April 31st,
                // or August 0th. Date object doesn't roundtrip those, so we eventually fail to load this data.
                // Don't even bother trying.
                if (!isCorrectDate(name)) {
                    LOGGER.fine("Skipping "+new File(dir,name));
                    return false;
                }
                return !name.startsWith("0000") && new File(dir,name).isDirectory();
            }

            private boolean isCorrectDate(String name) {
                try {
                    if(formatter.format(formatter.parse(name)).equals(name))
                        return true;
                } catch (ParseException e) {
                    // fall through
                }
                return false;
            }
        };
    }

    @Override
    protected R retrieve(File d) throws IOException {
        if(new File(d,"build.xml").exists()) {
            // if the build result file isn't in the directory, ignore it.
            try {
                R b = cons.create(d);
                b.onLoad();
                return b;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "could not load " + d, e);
            } catch (InstantiationError e) {
                LOGGER.log(Level.WARNING, "could not load " + d, e);
            }
        }
        return null;
    }

    /**
     * Backward compatibility method that notifies {@link RunMap} of who the owner is.
     *
     * Traditionally, this method blocked and loaded all the build records on the disk,
     * but now all the actual loading happens lazily.
     *
     * @param job
     *      Job that owns this map.
     * @param cons
     *      Used to create new instance of {@link Run}.
     * @deprecated
     *      Use {@link #RunMap(File, Constructor)}
     */
    public void load(Job job, Constructor<R> cons) {
        this.cons = cons;
        initBaseDir(job.getBuildDir());
    }

    private static final Logger LOGGER = Logger.getLogger(RunMap.class.getName());
}
