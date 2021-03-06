/*
 * Copyright (c) 2014, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.language.methods;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.Layouts;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.language.RubyGuards;
import org.jruby.truffle.language.RubyNode;
import org.jruby.truffle.language.control.RaiseException;
import org.jruby.truffle.language.control.TruffleFatalException;

public class ExceptionTranslatingNode extends RubyNode {

    private final UnsupportedOperationBehavior unsupportedOperationBehavior;

    @Child private RubyNode child;

    private final BranchProfile controlProfile = BranchProfile.create();
    private final BranchProfile arithmeticProfile = BranchProfile.create();
    private final BranchProfile unsupportedProfile = BranchProfile.create();
    private final BranchProfile errorProfile = BranchProfile.create();

    public ExceptionTranslatingNode(RubyContext context, SourceSection sourceSection, RubyNode child,
                                    UnsupportedOperationBehavior unsupportedOperationBehavior) {
        super(context, sourceSection);
        this.child = child;
        this.unsupportedOperationBehavior = unsupportedOperationBehavior;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        try {
            return child.execute(frame);
        } catch (ControlFlowException exception) {
            controlProfile.enter();
            throw exception;
        } catch (ArithmeticException exception) {
            arithmeticProfile.enter();
            throw new RaiseException(translate(exception));
        } catch (UnsupportedSpecializationException exception) {
            unsupportedProfile.enter();
            throw new RaiseException(translate(exception));
        } catch (TruffleFatalException exception) {
            errorProfile.enter();
            throw exception;
        } catch (StackOverflowError error) {
            errorProfile.enter();
            throw new RaiseException(translate(error));
        } catch (ThreadDeath death) {
            throw death;
        } catch (IllegalArgumentException e) {
            errorProfile.enter();
            throw new RaiseException(translate(e));
        } catch (Throwable exception) {
            errorProfile.enter();
            throw new RaiseException(translate(exception));
        }
    }

    @TruffleBoundary
    private DynamicObject translate(ArithmeticException exception) {
        if (getContext().getOptions().EXCEPTIONS_PRINT_JAVA) {
            exception.printStackTrace();
        }

        return coreExceptions().zeroDivisionError(this, exception);
    }

    @TruffleBoundary
    private DynamicObject translate(StackOverflowError error) {
        if (getContext().getOptions().EXCEPTIONS_PRINT_JAVA) {
            error.printStackTrace();
        }

        return coreExceptions().systemStackErrorStackLevelTooDeep(this, error);
    }

    @TruffleBoundary
    private DynamicObject translate(IllegalArgumentException exception) {
        if (getContext().getOptions().EXCEPTIONS_PRINT_JAVA) {
            exception.printStackTrace();
        }

        String message = exception.getMessage();

        if (message == null) {
            message = exception.toString();
        }

        return coreExceptions().argumentError(message, this);
    }

    @TruffleBoundary
    private DynamicObject translate(UnsupportedSpecializationException exception) {
        if (getContext().getOptions().EXCEPTIONS_PRINT_JAVA) {
            exception.printStackTrace();
        }

        final StringBuilder builder = new StringBuilder();
        builder.append("Truffle doesn't have a case for the ");
        builder.append(exception.getNode().getClass().getName());
        builder.append(" node with values of type ");

        for (Object value : exception.getSuppliedValues()) {
            builder.append(" ");

            if (value == null) {
                builder.append("null");
            } else if (value instanceof DynamicObject) {
                final DynamicObject dynamicObject = (DynamicObject) value;

                builder.append(Layouts.MODULE.getFields(Layouts.BASIC_OBJECT.getLogicalClass(dynamicObject)).getName());
                builder.append("(");
                builder.append(value.getClass().getName());
                builder.append(")");

                if (RubyGuards.isRubyArray(value)) {
                    final DynamicObject array = (DynamicObject) value;
                    builder.append("[");

                    if (Layouts.ARRAY.getStore(array) == null) {
                        builder.append("null");
                    } else {
                        builder.append(Layouts.ARRAY.getStore(array).getClass().getName());
                    }

                    builder.append(",");
                    builder.append(Layouts.ARRAY.getSize(array));
                    builder.append("]");
                } else if (RubyGuards.isRubyHash(value)) {
                    final Object store = Layouts.HASH.getStore((DynamicObject) value);

                    if (store == null) {
                        builder.append("[null]");
                    } else {
                        builder.append("[");
                        builder.append(store.getClass().getName());
                        builder.append("]");
                    }
                }
            } else {
                builder.append(value.getClass().getName());
            }

            if (value instanceof Number || value instanceof Boolean) {
                builder.append("=");
                builder.append(value.toString());
            }
        }

        switch (unsupportedOperationBehavior) {
            case TYPE_ERROR:
                return coreExceptions().typeError(builder.toString(), this, exception);
            case ARGUMENT_ERROR:
                return coreExceptions().argumentError(builder.toString(), this, exception);
            default:
                throw new UnsupportedOperationException();
        }
    }

    @TruffleBoundary
    public DynamicObject translate(Throwable throwable) {
        if (getContext().getOptions().EXCEPTIONS_PRINT_JAVA
                || getContext().getOptions().EXCEPTIONS_PRINT_UNCAUGHT_JAVA) {
            throwable.printStackTrace();
        }

        final String message = throwable.getMessage();
        final String reportedMessage;

        if (message != null && message.startsWith("LLVM error")) {
            reportedMessage = message;
        } else {
            final StringBuilder messageBuilder = new StringBuilder();
            messageBuilder.append(throwable.getClass().getSimpleName());
            messageBuilder.append(" ");
            if (message != null) {
                messageBuilder.append(message);
            } else {
                messageBuilder.append("<no message>");
            }

            if (throwable.getStackTrace().length > 0) {
                messageBuilder.append(" ");
                messageBuilder.append(throwable.getStackTrace()[0].toString());
            }
            reportedMessage = messageBuilder.toString();
        }

        return coreExceptions().internalError(reportedMessage, this, throwable);
    }

}
