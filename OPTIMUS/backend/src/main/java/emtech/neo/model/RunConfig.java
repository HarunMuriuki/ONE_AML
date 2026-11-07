package one.neo.model;

/**
 * Editable runtime settings (config.json). Controls how many Oracle rows a
 * single slot "Run" pulls before advancing its cursor to the next slice.
 */
public class RunConfig {

    private int rowsPerRun = 500;

    public int getRowsPerRun() { return rowsPerRun; }
    public void setRowsPerRun(int rowsPerRun) { this.rowsPerRun = rowsPerRun; }
}
