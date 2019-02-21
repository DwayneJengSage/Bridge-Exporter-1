package org.sagebionetworks.bridge.exporter.synapse;

public class ColumnDefinition  {
    private String name;
    private Integer maximumSize;
    private TransferMethod transferMethod;
    private String ddbName;
    private boolean sanitize;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(Integer maximumSize) {
        this.maximumSize = maximumSize;
    }

    public TransferMethod getTransferMethod() {
        return transferMethod;
    }

    public void setTransferMethod(TransferMethod transferMethod) {
        this.transferMethod = transferMethod;
    }

    public String getDdbName() {
        return ddbName;
    }

    public void setDdbName(String ddbName) {
        this.ddbName = ddbName;
    }

    public boolean getSanitize() {
        return sanitize;
    }

    public void setSanitize(boolean sanitize) {
        this.sanitize = sanitize;
    }

}
