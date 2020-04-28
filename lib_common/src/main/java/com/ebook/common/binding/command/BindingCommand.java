package com.ebook.common.binding.command;



public class BindingCommand<T> {
    private BindingAction execute;

    public BindingCommand(BindingAction execute) {
        this.execute = execute;
    }

    public void execute() {
        if (execute != null) {
            execute.call();
        }
    }
}
