package seedu.address.model;

import static java.util.Objects.requireNonNull;
import static seedu.address.commons.util.CollectionUtil.requireAllNonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import java.util.logging.Logger;

import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import seedu.address.commons.core.GuiSettings;
import seedu.address.commons.core.LogsCenter;
import seedu.address.logic.commands.exceptions.CommandException;
import seedu.address.model.person.Appointment;
import seedu.address.model.person.Person;
import seedu.address.storage.BackupManager;
import seedu.address.storage.Storage;


/**
 * Represents the in-memory model of the address book data.
 */
public class ModelManager implements Model {

    private static final Logger logger = LogsCenter.getLogger(ModelManager.class);
    private final AddressBook addressBook;
    private final UserPrefs userPrefs;
    private final Storage storage;
    private final BackupManager backupManager;
    private final FilteredList<Person> filteredPersons;
    private final Calendar calendar;
    private OperatingHours operatingHours;

    /**
     * Initializes a ModelManager with the given address book, user preferences, and storage.
     *
     * @param addressBook The address book data to initialize the model with.
     * @param userPrefs   The user preferences to initialize the model with.
     * @param storage     The storage to be used by the model for backup and restore operations.
     */
    public ModelManager(ReadOnlyAddressBook addressBook,
                        ReadOnlyUserPrefs userPrefs,
                        Storage storage) throws IOException {
        requireNonNull(addressBook);
        requireNonNull(userPrefs);

        logger.fine("Initializing with address book: " + addressBook
                + ", user prefs: " + userPrefs
                + ", and storage: " + storage);

        this.addressBook = new AddressBook(addressBook);
        this.userPrefs = new UserPrefs(userPrefs);
        this.storage = storage;
        this.backupManager = new BackupManager(Paths.get("backups"));
        this.filteredPersons = new FilteredList<>(this.addressBook.getPersonList());
        this.calendar = new Calendar(this.addressBook);
        this.operatingHours = new OperatingHours(); // TBC currently only sets default
    }

    public ModelManager() throws IOException {
        this(new AddressBook(), new UserPrefs(), null);
    }

    // ============ User Preferences Methods ============================================================

    @Override
    public void setUserPrefs(ReadOnlyUserPrefs userPrefs) {
        requireNonNull(userPrefs);
        this.userPrefs.resetData(userPrefs);
    }

    @Override
    public ReadOnlyUserPrefs getUserPrefs() {
        return userPrefs;
    }

    @Override
    public GuiSettings getGuiSettings() {
        return userPrefs.getGuiSettings();
    }

    @Override
    public void setGuiSettings(GuiSettings guiSettings) {
        requireNonNull(guiSettings);
        userPrefs.setGuiSettings(guiSettings);
    }

    @Override
    public Path getAddressBookFilePath() {
        return userPrefs.getAddressBookFilePath();
    }

    @Override
    public void setAddressBookFilePath(Path addressBookFilePath) {
        requireNonNull(addressBookFilePath);
        userPrefs.setAddressBookFilePath(addressBookFilePath);
    }

    // ============ Address Book Methods ================================================================

    @Override
    public void setAddressBook(ReadOnlyAddressBook addressBook) {
        this.addressBook.resetData(addressBook);
        this.calendar.setAppointments(addressBook);
    }

    @Override
    public ReadOnlyAddressBook getAddressBook() {
        return addressBook;
    }

    @Override
    public boolean hasPerson(Person person) {
        requireNonNull(person);
        return addressBook.hasPerson(person);
    }

    @Override
    public Calendar getCalendar() {
        return calendar;
    }



    @Override
    public boolean hasAppointment(Person person) {
        return calendar.hasAppointment(person);
    }

    @Override
    public void deletePerson(Person target) {
        triggerBackup("delete", target);
        addressBook.removePerson(target);
        calendar.deleteAppointment(target);
    }

    @Override
    public void addPerson(Person person) {
        addressBook.addPerson(person);
        calendar.addAppointment(person);
        updateFilteredPersonList(PREDICATE_SHOW_ALL_PERSONS);
    }

    @Override
    public void setPerson(Person target, Person editedPerson) {
        requireAllNonNull(target, editedPerson);
        addressBook.setPerson(target, editedPerson);
        calendar.setAppointment(target, editedPerson);
    }

    @Override
    public OperatingHours getOperatingHours() {
        return operatingHours;
    }

    @Override
    public boolean setOperatingHours(LocalTime openingHour, LocalTime closingHour) {
        OperatingHours newOperatingHours = new OperatingHours(openingHour, closingHour);
        if (newOperatingHours.isCalenderValid(calendar.getAppointments())) {
            operatingHours = newOperatingHours;
            return true;
        }
        return false;
    }

    @Override
    public boolean appointmentWithinOperatingHours(Appointment appointment) {
        requireNonNull(appointment);
        return operatingHours.isWithinOperatingHours(appointment);
    }

    protected void triggerBackup(String action, Person target) {
        if ("delete".equals(action)) {
            try {
                String backupName = action + "_" + target.getName();
                backupManager.triggerBackup(storage.getAddressBookFilePath(), backupName);
                logger.info("Backup triggered for deletion: " + backupName);
            } catch (IOException e) {
                logger.warning("Backup failed for deletion: " + e.getMessage());
            }
        }
    }

    // ============ Filtered Person List Accessors =======================================================

    @Override
    public ObservableList<Person> getFilteredPersonList() {
        return filteredPersons;
    }

    @Override
    public void updateFilteredPersonList(Predicate<Person> predicate) {
        requireNonNull(predicate);
        filteredPersons.setPredicate(predicate);
    }

    // ============ Backup and Restore Methods ===========================================================

    /**
     * Cleans up old backups, retaining only the latest `maxBackups`.
     *
     * @param maxBackups The number of backups to retain.
     * @throws IOException If there is an error during cleanup.
     */
    public void cleanOldBackups(int maxBackups) throws IOException {
        if (storage != null) {
            logger.info("Cleaning old backups, keeping the latest " + maxBackups + " backups.");
            storage.cleanOldBackups(maxBackups);
        } else {
            throw new IOException("Storage is not initialized!");
        }
    }

    @Override
    public void backupData(String fileName) throws CommandException {
        if (storage == null) {
            throw new CommandException("Failed to create manual backup: Storage is not initialized!");
        }

        if (fileName == null || fileName.isBlank()) {
            // Use timestamp if no filename provided
            fileName = "manual-backup_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
        }

        try {
            backupManager.triggerBackup(storage.getAddressBookFilePath(), fileName);
            logger.info("Manual backup created: " + fileName);
        } catch (IOException e) {
            logger.warning("Manual backup failed: " + e.getMessage());
            throw new CommandException("Failed to create manual backup: " + e.getMessage());
        }
    }

    // ============ Equality and Storage Access Methods ==================================================

    @Override
    public Storage getStorage() {
        return storage;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof ModelManager)) {
            return false;
        }

        ModelManager otherModelManager = (ModelManager) other;
        return addressBook.equals(otherModelManager.addressBook)
                && userPrefs.equals(otherModelManager.userPrefs)
                && filteredPersons.equals(otherModelManager.filteredPersons)
                && calendar.equals(otherModelManager.calendar);
    }
}
