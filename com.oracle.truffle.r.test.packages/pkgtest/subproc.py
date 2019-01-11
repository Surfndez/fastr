#
# Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 3 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 3 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 3 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
import logging
from threading import Thread
import time, signal, errno, sys, os, subprocess

from util import abort

ERROR_TIMEOUT = 0x700000000 # not 32 bits
_currentSubprocesses = []

def get_os():
    """
    Get a canonical form of sys.platform.
    """
    if sys.platform.startswith('darwin'):
        return 'darwin'
    elif sys.platform.startswith('linux'):
        return 'linux'
    elif sys.platform.startswith('openbsd'):
        return 'openbsd'
    elif sys.platform.startswith('sunos'):
        return 'solaris'
    elif sys.platform.startswith('win32'):
        return 'windows'
    elif sys.platform.startswith('cygwin'):
        return 'cygwin'
    else:
        abort(1, 'Unknown operating system ' + sys.platform)

def _addSubprocess(p, args):
    entry = (p, args)
    logging.debug('[{}: started subprocess {}: {}]'.format(os.getpid(), p.pid, args))
    _currentSubprocesses.append(entry)
    return entry

def _removeSubprocess(entry):
    if entry and entry in _currentSubprocesses:
        try:
            _currentSubprocesses.remove(entry)
        except:
            pass


def waitOn(p):
    if get_os() == 'windows':
        # on windows use a poll loop, otherwise signal does not get handled
        retcode = None
        while retcode == None:
            retcode = p.poll()
            time.sleep(0.05)
    else:
        retcode = p.wait()
    return retcode


def _kill_process(pid, sig):
    """
    Sends the signal `sig` to the process identified by `pid`. If `pid` is a process group
    leader, then signal is sent to the process group id.
    """
    pgid = os.getpgid(pid)
    try:
        logging.debug('[{} sending {} to {}]'.format(os.getpid(), sig, pid))
        if pgid == pid:
            os.killpg(pgid, sig)
        else:
            os.kill(pid, sig)
        return True
    except:
        logging.error('Error killing subprocess ' + str(pid) + ': ' + str(sys.exc_info()[1]))
        return False





def _waitWithTimeout(process, args, timeout, nonZeroIsFatal=True):
    def _waitpid(pid):
        while True:
            try:
                return os.waitpid(pid, os.WNOHANG)
            except OSError, e:
                if e.errno == errno.EINTR:
                    continue
                raise

    def _returncode(status):
        if os.WIFSIGNALED(status):
            return -os.WTERMSIG(status)
        elif os.WIFEXITED(status):
            return os.WEXITSTATUS(status)
        else:
            # Should never happen
            raise RuntimeError("Unknown child exit status!")

    end = time.time() + timeout
    delay = 0.0005
    while True:
        (pid, status) = _waitpid(process.pid)
        if pid == process.pid:
            return _returncode(status)
        remaining = end - time.time()
        if remaining <= 0:
            msg = 'Process timed out after {0} seconds: {1}'.format(timeout, ' '.join(args))
            if nonZeroIsFatal:
                abort(1, msg)
            else:
                logging.error(msg)
                _kill_process(process.pid, signal.SIGKILL)
                return ERROR_TIMEOUT
        delay = min(delay * 2, remaining, .05)
        time.sleep(delay)




def pkgtest_run(args, nonZeroIsFatal=True, out=None, err=None, cwd=None, timeout=None, env=None, **kwargs):
    """
    Imported from MX.
    Run a command in a subprocess, wait for it to complete and return the exit status of the process.
    If the command times out, it kills the subprocess and returns `ERROR_TIMEOUT` if `nonZeroIsFatal`
    is false, otherwise it kills all subprocesses and raises a SystemExit exception.
    If the exit status of the command is non-zero, mx is exited with the same exit status if
    `nonZeroIsFatal` is true, otherwise the exit status is returned.
    Each line of the standard output and error streams of the subprocess are redirected to
    out and err if they are callable objects.
    """

    assert isinstance(args, (list, tuple)), "'args' must be a list or tuple: " + str(args)
    for arg in args:
        assert isinstance(arg, (str, bytes)), 'argument is not a string: ' + str(arg)

    if env is None:
        env = os.environ.copy()

    msg = 'Environment variables:\n'
    msg += '\n'.join(['    ' + key + '=' + env[key] for key in env.keys()])
    logging.debug(msg)

    sub = None

    try:
        if timeout or get_os() == 'windows':
            # TODO windows
            #preexec_fn, creationflags = _get_new_progress_group_args()
            pass
        else:
            preexec_fn, creationflags = (None, 0)

        def redirect(stream, f):
            for line in iter(stream.readline, ''):
                f(line)
            stream.close()
        stdout = out if not callable(out) else subprocess.PIPE
        stderr = err if not callable(err) else subprocess.PIPE
        p = subprocess.Popen(args, cwd=cwd, stdout=stdout, stderr=stderr, preexec_fn=preexec_fn, creationflags=creationflags, env=env, **kwargs)
        sub = _addSubprocess(p, args)
        joiners = []
        if callable(out):
            t = Thread(target=redirect, args=(p.stdout, out))
            # Don't make the reader thread a daemon otherwise output can be droppped
            t.start()
            joiners.append(t)
        if callable(err):
            t = Thread(target=redirect, args=(p.stderr, err))
            # Don't make the reader thread a daemon otherwise output can be droppped
            t.start()
            joiners.append(t)
        while any([t.is_alive() for t in joiners]):
            # Need to use timeout otherwise all signals (including CTRL-C) are blocked
            # see: http://bugs.python.org/issue1167930
            for t in joiners:
                t.join(10)
        if timeout is None or timeout == 0:
            while True:
                try:
                    retcode = waitOn(p)
                    break
                except KeyboardInterrupt:
                    if get_os() == 'windows':
                        p.terminate()
                    else:
                        # Propagate SIGINT to subprocess. If the subprocess does not
                        # handle the signal, it will terminate and this loop exits.
                        _kill_process(p.pid, signal.SIGINT)
        else:
            if get_os() == 'windows':
                abort('Use of timeout not (yet) supported on Windows')
            retcode = _waitWithTimeout(p, args, timeout, nonZeroIsFatal)
    except OSError as e:
        if not nonZeroIsFatal:
            raise e
        abort('Error executing \'' + ' '.join(args) + '\': ' + str(e))
    except KeyboardInterrupt:
        abort(1, killsig=signal.SIGINT)
    finally:
        _removeSubprocess(sub)

    if retcode and nonZeroIsFatal:
        logging.debug(subprocess.CalledProcessError(retcode, ' '.join(args)))
        abort(retcode, '[exit code: ' + str(retcode) + ']')

    return retcode